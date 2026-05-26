import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/chat/utils/stream_text_merge.dart';
import 'package:ui/features/home/pages/command_overlay/constants/messages.dart';
import 'package:ui/features/home/pages/chat/mixins/agent_stream_handler.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/agent_tool_card_projection.dart';
import 'package:ui/services/agent_stream_reducer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/codex_event_reducer.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/utils/data_parser.dart';

const String kChatRuntimeModeNormal = 'normal';
const String kChatRuntimeModeOpenClaw = 'openclaw';
const String kChatRuntimeModeCodex = 'codex';
const int _kStreamingTextChunkFlushThreshold = 5;
const Duration _kStreamingTextFlushDelay = Duration(milliseconds: 80);
final RegExp _markdownFenceLinePattern = RegExp(r'^[ \t]{0,3}(`{3,}|~{3,})');

enum _StreamingTextStreamKind {
  pureChatReply,
  agentReply,
  pureChatThinking,
  agentThinking,
}

bool _endsInsideMarkdownFence(String text) {
  var offset = 0;
  String? fenceMarker;
  var fenceLength = 0;

  while (offset <= text.length) {
    final newlineIndex = text.indexOf('\n', offset);
    final lineEnd = newlineIndex == -1 ? text.length : newlineIndex;
    var line = text.substring(offset, lineEnd);
    if (line.endsWith('\r')) {
      line = line.substring(0, line.length - 1);
    }

    final match = _markdownFenceLinePattern.firstMatch(line);
    if (match != null) {
      final marker = match.group(1)!;
      final markerChar = marker[0];
      if (fenceMarker == null) {
        fenceMarker = markerChar;
        fenceLength = marker.length;
      } else if (markerChar == fenceMarker && marker.length >= fenceLength) {
        fenceMarker = null;
        fenceLength = 0;
      }
    }

    if (newlineIndex == -1) {
      break;
    }
    offset = newlineIndex + 1;
  }

  return fenceMarker != null;
}

class _StreamingTextBatchState {
  _StreamingTextBatchState({
    required this.taskId,
    required this.kind,
    required this.latestText,
    required this.lastFlushedText,
  });

  final String taskId;
  final _StreamingTextStreamKind kind;
  String latestText;
  String lastFlushedText;
  int pendingChunkCount = 0;

  bool get hasPendingFlush => latestText != lastFlushedText;

  bool get reachedFlushThreshold =>
      pendingChunkCount >= _kStreamingTextChunkFlushThreshold;

  /// 自上次 flush 以来的新增文本中是否包含可安全立即刷新的换行。
  ///
  /// 普通 Markdown 换行仍会尽快显示；但 fenced code block 内的每一行都触发
  /// Markdown 重排会让长代码流明显卡顿，所以代码块未闭合时只走批量/定时刷新。
  bool get containsFlushableNewlineSinceFlush {
    if (latestText.length <= lastFlushedText.length) return false;
    if (latestText.indexOf('\n', lastFlushedText.length) < 0) return false;
    return !_endsInsideMarkdownFence(latestText);
  }

  void stage(String nextText) {
    if (nextText == latestText) {
      return;
    }
    latestText = nextText;
    pendingChunkCount += 1;
  }

  void markFlushed() {
    lastFlushedText = latestText;
    pendingChunkCount = 0;
  }
}

class ChatConversationRuntimeState {
  ChatConversationRuntimeState({
    required this.conversationId,
    required this.mode,
  }) : chatIslandDisplayLayer = mode == kChatRuntimeModeNormal
           ? ChatIslandDisplayLayer.tools
           : ChatIslandDisplayLayer.mode;

  final int conversationId;
  final String mode;

  ConversationModel? conversation;
  final ObservableChatMessageList messages = ObservableChatMessageList();
  final Map<String, String> currentAiMessages = <String, String>{};
  final Map<String, String> currentThinkingMessages = <String, String>{};
  final Map<String, AgentStreamTaskState> agentStreamStates =
      <String, AgentStreamTaskState>{};
  final Map<String, _StreamingTextBatchState> _streamingTextBatches =
      <String, _StreamingTextBatchState>{};
  final Map<String, Timer> _streamingTextFlushTimers = <String, Timer>{};
  final Map<String, Timer> toolEventFlushTimers = <String, Timer>{};
  final Map<String, int> codexEntrySequences = <String, int>{};
  final Map<String, int> codexEntryStartTimes = <String, int>{};
  int codexNextEntrySequence = 0;
  bool isAiResponding = false;
  bool isContextCompressing = false;
  bool isCheckingExecutableTask = false;
  bool isSubmittingVlmReply = false;
  String? vlmInfoQuestion;
  String deepThinkingContent = '';
  bool isDeepThinking = false;
  String? currentDispatchTaskId;
  int currentThinkingStage = 1;
  bool isInputAreaVisible = true;
  bool isExecutingTask = false;

  String? lastAgentTaskId;
  String? activeToolCardId;
  String? activeThinkingCardId;
  String? activeContextCompactionMarkerId;
  String? pendingAgentTextTaskId;
  String? waitingThinkingBeforeAgentTextTaskId;
  bool pendingThinkingRoundSplit = false;
  int toolCardSequence = 0;
  int thinkingRound = 0;
  ChatIslandDisplayLayer chatIslandDisplayLayer;
  String? lastAgentToolType;
  ChatBrowserSessionSnapshot? browserSessionSnapshot;

  bool get hasInFlightTask =>
      isAiResponding ||
      isCheckingExecutableTask ||
      isExecutingTask ||
      currentDispatchTaskId != null ||
      currentAiMessages.isNotEmpty;

  Set<String> get activeAgentTaskIds {
    final ids = <String>{
      ...agentStreamStates.keys,
      ...currentAiMessages.keys,
      ...currentThinkingMessages.keys,
    };
    final currentTaskId = currentDispatchTaskId?.trim() ?? '';
    if (currentTaskId.isNotEmpty) {
      ids.add(currentTaskId);
    }
    final lastTaskId = lastAgentTaskId?.trim() ?? '';
    if (isAiResponding && lastTaskId.isNotEmpty) {
      ids.add(lastTaskId);
    }
    final pendingTaskId = pendingAgentTextTaskId?.trim() ?? '';
    if (pendingTaskId.isNotEmpty) {
      ids.add(pendingTaskId);
    }
    return ids;
  }

  void dispose() {
    agentStreamStates.clear();
    _streamingTextBatches.clear();
    for (final timer in _streamingTextFlushTimers.values) {
      timer.cancel();
    }
    _streamingTextFlushTimers.clear();
    for (final timer in toolEventFlushTimers.values) {
      timer.cancel();
    }
    toolEventFlushTimers.clear();
    codexEntrySequences.clear();
    codexEntryStartTimes.clear();
    messages.dispose();
  }
}

class _TaskBinding {
  const _TaskBinding({required this.conversationId, required this.mode});

  final int conversationId;
  final String mode;
}

class _PendingPersistenceRequest {
  _PendingPersistenceRequest({
    required this.conversationId,
    required this.mode,
    required this.timer,
    this.generateSummary = false,
    this.markComplete = false,
    this.persistMessages = false,
  });

  final int conversationId;
  final String mode;
  final Timer timer;
  final bool generateSummary;
  final bool markComplete;
  final bool persistMessages;
}

class ChatConversationRuntimeCoordinator extends ChangeNotifier {
  ChatConversationRuntimeCoordinator._();

  static final ChatConversationRuntimeCoordinator instance =
      ChatConversationRuntimeCoordinator._();

  static const int _maxTerminalOutputChars = 64 * 1024;
  static const Duration _toolEventFlushDelay = Duration(milliseconds: 80);
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

  String _agentTextBaseId(String taskId) => '$taskId-text';

  final AgentStreamReducer _agentStreamReducer = const AgentStreamReducer();
  final CodexEventReducer _codexEventReducer = const CodexEventReducer();
  final Map<String, ChatConversationRuntimeState> _runtimes =
      <String, ChatConversationRuntimeState>{};
  final Map<String, _TaskBinding> _taskBindings = <String, _TaskBinding>{};
  final Map<String, _PendingPersistenceRequest> _pendingPersistence =
      <String, _PendingPersistenceRequest>{};
  final Set<String> _ephemeralRuntimeKeys = <String>{};

  bool _initialized = false;

  void ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    unawaited(VoicePlaybackCoordinator.instance.ensureInitialized());

    AssistsMessageService.initialize();
    AssistsMessageService.addOnChatTaskMessageCallBack(_handleChatTaskMessage);
    AssistsMessageService.addOnChatTaskMessageEndCallBack(
      _handleChatTaskMessageEnd,
    );
    AssistsMessageService.setOnAgentStreamEventCallback(
      _handleAgentStreamEvent,
    );
    AssistsMessageService.setOnAgentPromptTokenUsageCallback(
      _handleAgentPromptTokenUsageChanged,
    );
    AssistsMessageService.setOnAgentContextCompactionStateCallback(
      _handleAgentContextCompactionStateChanged,
    );
    AssistsMessageService.setOnVLMRequestUserInputCallBack(
      _handleVlmRequestUserInput,
    );
    AssistsMessageService.setOnVLMTaskFinishCallBack(_handleVlmTaskFinish);
  }

  ChatConversationRuntimeState? runtimeFor({
    required int conversationId,
    required String mode,
  }) {
    return _runtimes[_runtimeKey(conversationId: conversationId, mode: mode)];
  }

  ChatConversationRuntimeState ensureRuntime({
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final existing = _runtimes[key];
    final runtime =
        existing ??
        ChatConversationRuntimeState(
          conversationId: conversationId,
          mode: mode,
        );
    if (existing == null) {
      if (initialChatIslandDisplayLayer != null) {
        runtime.chatIslandDisplayLayer = initialChatIslandDisplayLayer;
      }
      _runtimes[key] = runtime;
    }
    if (runtime.messages.isEmpty && initialMessages != null) {
      runtime.messages.addAll(initialMessages);
    }
    if (conversation != null) {
      runtime.conversation = conversation;
    }
    return runtime;
  }

  ChatConversationRuntimeState ensureEphemeralRuntime({
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      initialMessages: initialMessages,
      conversation: conversation,
      initialChatIslandDisplayLayer: initialChatIslandDisplayLayer,
    );
    _ephemeralRuntimeKeys.add(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
    return runtime;
  }

  bool isEphemeralRuntime({required int conversationId, required String mode}) {
    return _ephemeralRuntimeKeys.contains(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
  }

  void replaceConversationSnapshot({
    required int conversationId,
    required String mode,
    required List<ChatMessageModel> messages,
    ConversationModel? conversation,
    bool isAiResponding = false,
    bool isContextCompressing = false,
    bool isCheckingExecutableTask = false,
    bool isSubmittingVlmReply = false,
    String? vlmInfoQuestion,
    Map<String, String>? currentAiMessages,
    Map<String, String>? currentThinkingMessages,
    String deepThinkingContent = '',
    bool isDeepThinking = false,
    String? currentDispatchTaskId,
    int currentThinkingStage = 1,
    bool isInputAreaVisible = true,
    bool isExecutingTask = false,
    String? lastAgentTaskId,
    String? activeToolCardId,
    String? activeThinkingCardId,
    String? activeContextCompactionMarkerId,
    String? pendingAgentTextTaskId,
    bool pendingThinkingRoundSplit = false,
    int toolCardSequence = 0,
    int thinkingRound = 0,
    ChatIslandDisplayLayer chatIslandDisplayLayer = ChatIslandDisplayLayer.mode,
    String? lastAgentToolType,
    ChatBrowserSessionSnapshot? browserSessionSnapshot,
  }) {
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
    );
    _flushRuntimeStreamingText(runtime);
    runtime.messages.replaceAllMessages(messages);
    runtime.conversation = conversation ?? runtime.conversation;
    runtime.isAiResponding = isAiResponding;
    runtime.isContextCompressing = isContextCompressing;
    runtime.isCheckingExecutableTask = isCheckingExecutableTask;
    runtime.isSubmittingVlmReply = isSubmittingVlmReply;
    runtime.vlmInfoQuestion = vlmInfoQuestion;
    runtime.currentAiMessages
      ..clear()
      ..addAll(currentAiMessages ?? const <String, String>{});
    runtime.currentThinkingMessages
      ..clear()
      ..addAll(currentThinkingMessages ?? const <String, String>{});
    runtime.deepThinkingContent = deepThinkingContent;
    runtime.isDeepThinking = isDeepThinking;
    runtime.currentDispatchTaskId = currentDispatchTaskId;
    runtime.currentThinkingStage = currentThinkingStage;
    runtime.isInputAreaVisible = isInputAreaVisible;
    runtime.isExecutingTask = isExecutingTask;
    runtime.lastAgentTaskId = lastAgentTaskId;
    runtime.activeToolCardId = activeToolCardId;
    runtime.activeThinkingCardId = activeThinkingCardId;
    runtime.activeContextCompactionMarkerId = activeContextCompactionMarkerId;
    runtime.pendingAgentTextTaskId = pendingAgentTextTaskId;
    runtime.waitingThinkingBeforeAgentTextTaskId = null;
    runtime.pendingThinkingRoundSplit = pendingThinkingRoundSplit;
    runtime.toolCardSequence = toolCardSequence;
    runtime.thinkingRound = thinkingRound;
    runtime.chatIslandDisplayLayer = chatIslandDisplayLayer;
    runtime.lastAgentToolType = lastAgentToolType;
    runtime.browserSessionSnapshot = browserSessionSnapshot;
    runtime.agentStreamStates.clear();
    runtime._streamingTextBatches.clear();
    runtime.codexEntrySequences.clear();
    runtime.codexEntryStartTimes.clear();
    runtime.codexNextEntrySequence = 0;
    notifyListeners();
  }

  void registerTask({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    ensureInitialized();
    ensureRuntime(conversationId: conversationId, mode: mode);
    _taskBindings[taskId] = _TaskBinding(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void primePureChatThinking({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    ensureInitialized();
    final runtime = ensureRuntime(conversationId: conversationId, mode: mode);
    _taskBindings[taskId] = _TaskBinding(
      conversationId: conversationId,
      mode: mode,
    );

    runtime.lastAgentTaskId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.isDeepThinking = true;

    if (runtime.thinkingRound == 0) {
      runtime.thinkingRound = 1;
      runtime.activeThinkingCardId = _baseThinkingCardId(taskId);
      final exists = runtime.messages.any(
        (msg) => msg.id == runtime.activeThinkingCardId,
      );
      if (exists) {
        _updateThinkingCard(
          runtime,
          taskId,
          cardId: runtime.activeThinkingCardId,
          isLoading: true,
          stage: ThinkingStage.thinking.value,
          lockCompleted: false,
        );
      } else {
        _createThinkingCard(
          runtime,
          taskId,
          cardId: runtime.activeThinkingCardId,
          isLoading: true,
          stage: ThinkingStage.thinking.value,
        );
      }
      notifyListeners();
      schedulePersistRuntimeConversation(
        conversationId: conversationId,
        mode: mode,
      );
      return;
    }

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    runtime.pendingThinkingRoundSplit = true;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void unregisterTask(String taskId) {
    final runtime = _runtimeForTask(taskId);
    if (runtime != null) {
      _flushStreamingTextForTask(runtime, taskId);
      _clearStreamingTextBatchesForTask(runtime, taskId);
      runtime.agentStreamStates.remove(taskId);
      runtime.currentAiMessages.remove(taskId);
      runtime.currentThinkingMessages.remove(taskId);
      runtime.isAiResponding = false;
      runtime.isExecutingTask = false;
      runtime.isCheckingExecutableTask = false;
      runtime.deepThinkingContent = '';
      runtime.isDeepThinking = false;
      runtime.activeToolCardId = null;
      runtime.activeThinkingCardId = null;
      runtime.pendingAgentTextTaskId = null;
      runtime.waitingThinkingBeforeAgentTextTaskId = null;
      runtime.pendingThinkingRoundSplit = false;
      if (runtime.currentDispatchTaskId == taskId) {
        runtime.currentDispatchTaskId = null;
      }
      if (runtime.lastAgentTaskId == taskId) {
        runtime.lastAgentTaskId = null;
      }
    }
    _taskBindings.remove(taskId);
  }

  CodexReduceResult applyCodexEvent({
    required int conversationId,
    required Map<String, dynamic> event,
    ConversationModel? conversation,
  }) {
    ensureInitialized();
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeCodex,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    final result = _codexEventReducer.reduce(runtime: runtime, event: event);
    if (result.handled) {
      notifyListeners();
      if (!isEphemeralRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeCodex,
      )) {
        schedulePersistRuntimeConversation(
          conversationId: conversationId,
          mode: kChatRuntimeModeCodex,
          persistMessages: true,
        );
      }
    }
    return result;
  }

  void clearPureChatThinking({
    required String taskId,
    required int conversationId,
    required String mode,
    bool removeCard = true,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    runtime.currentThinkingMessages.remove(taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.lastAgentTaskId = null;
    runtime.activeThinkingCardId = null;
    runtime.pendingThinkingRoundSplit = false;
    runtime.thinkingRound = 0;
    if (removeCard) {
      runtime.messages.removeWhere((message) {
        final cardData = message.cardData;
        return message.type == 2 &&
            cardData?['type'] == 'deep_thinking' &&
            (cardData?['taskID'] ?? '').toString() == taskId;
      });
    }
    _clearStreamingTextBatchesForTask(runtime, taskId);
    notifyListeners();
  }

  @visibleForTesting
  void resetForTest() {
    for (final request in _pendingPersistence.values) {
      request.timer.cancel();
    }
    _pendingPersistence.clear();
    for (final runtime in _runtimes.values) {
      _flushRuntimeStreamingText(runtime);
      runtime.dispose();
    }
    _runtimes.clear();
    _taskBindings.clear();
    _ephemeralRuntimeKeys.clear();
  }

  void clearConversationRuntimeSession({
    required int conversationId,
    required String mode,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    _flushRuntimeStreamingText(runtime);
    runtime.currentDispatchTaskId = null;
    runtime.isContextCompressing = false;
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.currentThinkingMessages.clear();
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.lastAgentTaskId = null;
    runtime.pendingAgentTextTaskId = null;
    runtime.waitingThinkingBeforeAgentTextTaskId = null;
    runtime.activeToolCardId = null;
    runtime.activeThinkingCardId = null;
    runtime.activeContextCompactionMarkerId = null;
    runtime.agentStreamStates.clear();
    runtime.pendingThinkingRoundSplit = false;
    runtime.toolCardSequence = 0;
    runtime.thinkingRound = 0;
    runtime._streamingTextBatches.clear();
    runtime.codexEntrySequences.clear();
    runtime.codexEntryStartTimes.clear();
    runtime.codexNextEntrySequence = 0;
    notifyListeners();
  }

  void discardConversationRuntime({
    required int conversationId,
    required String mode,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime != null) {
      _flushRuntimeStreamingText(runtime);
    }
    _cancelPendingPersistence(conversationId: conversationId, mode: mode);
    _ephemeralRuntimeKeys.remove(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
    _taskBindings.removeWhere(
      (_, binding) =>
          binding.conversationId == conversationId && binding.mode == mode,
    );
    final removed = _runtimes.remove(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
    if (removed != null) {
      removed.dispose();
      notifyListeners();
    }
  }

  void interruptActiveToolCard({
    required int conversationId,
    required String mode,
    String? summary,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    final cardId = runtime.activeToolCardId;
    if (cardId == null) return;

    final index = runtime.messages.indexWhere((msg) => msg.id == cardId);
    if (index == -1) {
      runtime.activeToolCardId = null;
      notifyListeners();
      return;
    }

    final existingCardData = Map<String, dynamic>.from(
      runtime.messages[index].cardData ?? const {},
    );
    if ((existingCardData['status'] ?? '').toString() != 'running') {
      runtime.activeToolCardId = null;
      return;
    }
    existingCardData['status'] = 'interrupted';
    existingCardData['success'] = false;
    if (summary != null && summary.trim().isNotEmpty) {
      existingCardData['summary'] = summary.trim();
    }
    runtime.messages[index] = runtime.messages[index].copyWith(
      content: {'cardData': existingCardData, 'id': cardId},
    );
    runtime.activeToolCardId = null;
    notifyListeners();
  }

  bool _finalizeRunningToolCardsForTask(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String status = 'success',
    String? summary,
    bool Function(Map<String, dynamic> cardData)? shouldFinalize,
  }) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) return false;

    var changed = false;
    final finalizedCardIds = <String>{};
    final isSuccess = status == 'success';
    for (var index = 0; index < runtime.messages.length; index++) {
      final message = runtime.messages[index];
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
      // Terminal and browser tools manage their own streaming lifecycle.
      final toolType = (rawCardData['toolType'] ?? '').toString();
      if (toolType == 'terminal' || toolType == 'browser') {
        continue;
      }
      if (shouldFinalize != null && !shouldFinalize(rawCardData)) {
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
      runtime.messages[index] = message.copyWith(
        content: {'cardData': cardData, 'id': message.id},
      );
      changed = true;
    }

    if (finalizedCardIds.isNotEmpty) {
      final state = runtime.agentStreamStates[normalizedTaskId];
      if (state != null) {
        final activeToolEntryIds = Set<String>.from(state.activeToolEntryIds)
          ..removeWhere(finalizedCardIds.contains);
        if (activeToolEntryIds.length != state.activeToolEntryIds.length) {
          runtime.agentStreamStates[normalizedTaskId] = state.copyWith(
            activeToolEntryIds: activeToolEntryIds,
            activeToolFallbackEntryIds: state.activeToolFallbackEntryIds
                .where(activeToolEntryIds.contains)
                .toSet(),
          );
        }
        runtime.activeToolCardId = activeToolEntryIds.isEmpty
            ? null
            : activeToolEntryIds.last;
      }
      if (finalizedCardIds.contains(runtime.activeToolCardId)) {
        runtime.activeToolCardId = null;
      }
    }
    return changed;
  }

  String _firstNonEmpty(Iterable<Object?> values) {
    for (final value in values) {
      final text = value?.toString().trim() ?? '';
      if (text.isNotEmpty) return text;
    }
    return '';
  }

  Future<void> persistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
  }) async {
    _cancelPendingPersistence(conversationId: conversationId, mode: mode);
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    _flushRuntimeStreamingText(runtime);
    if (runtime.messages.isEmpty) return;

    final snapshotMessages = List<ChatMessageModel>.from(runtime.messages);
    final snapshotConversation = runtime.conversation;
    final conversationMode = _conversationModeFromRuntimeMode(
      mode,
      conversation: snapshotConversation,
    );
    final now = DateTime.now().millisecondsSinceEpoch;
    final lastMessage = snapshotMessages.isNotEmpty
        ? (snapshotMessages[0].text ?? '')
        : '';
    final messageCount = snapshotMessages.length;
    final firstUserMessage = snapshotMessages.firstWhere(
      (m) => m.user == 1,
      orElse: () => ChatMessageModel.userMessage("default"),
    );
    final userText = firstUserMessage.text ?? 'conversation';
    final title = userText.length > 20
        ? '${userText.substring(0, 20)}...'
        : userText;

    String? summary = snapshotConversation?.summary;
    if (generateSummary) {
      final history = _buildConversationHistoryText(snapshotMessages);
      summary = history.isEmpty
          ? null
          : await ConversationService.generateConversationSummary(
              conversationHistory: history,
            );
    }

    final baseConversation =
        (snapshotConversation?.mode == conversationMode
            ? snapshotConversation
            : snapshotConversation?.copyWith(mode: conversationMode)) ??
        ConversationModel(
          id: conversationId,
          mode: conversationMode,
          title: title,
          summary: summary,
          status: 0,
          lastMessage: lastMessage,
          messageCount: messageCount,
          createdAt: now,
          updatedAt: now,
        );

    final updatedConversation = baseConversation.copyWith(
      title: baseConversation.title.isEmpty ? title : baseConversation.title,
      summary: summary ?? baseConversation.summary,
      lastMessage: lastMessage,
      messageCount: messageCount,
      updatedAt: now,
    );

    await ConversationService.updateConversation(updatedConversation);
    if (persistMessages) {
      await ConversationHistoryService.saveConversationMessages(
        conversationId,
        snapshotMessages,
        mode: conversationMode,
      );
    }
    runtime.conversation = updatedConversation;
    if (markComplete) {
      await ConversationService.completeConversation(
        conversationId,
        mode: conversationMode,
      );
    }
  }

  void schedulePersistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
    Duration delay = const Duration(milliseconds: 350),
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    if (_ephemeralRuntimeKeys.contains(key)) {
      return;
    }
    final previous = _pendingPersistence[key];
    previous?.timer.cancel();
    final nextGenerateSummary =
        generateSummary || (previous?.generateSummary ?? false);
    final nextMarkComplete = markComplete || (previous?.markComplete ?? false);
    final nextPersistMessages =
        persistMessages || (previous?.persistMessages ?? false);
    final timer = Timer(delay, () {
      _pendingPersistence.remove(key);
      unawaited(
        persistRuntimeConversation(
          conversationId: conversationId,
          mode: mode,
          generateSummary: nextGenerateSummary,
          markComplete: nextMarkComplete,
          persistMessages: nextPersistMessages,
        ),
      );
    });
    _pendingPersistence[key] = _PendingPersistenceRequest(
      conversationId: conversationId,
      mode: mode,
      timer: timer,
      generateSummary: nextGenerateSummary,
      markComplete: nextMarkComplete,
      persistMessages: nextPersistMessages,
    );
  }

  Future<void> flushPendingPersistence({
    required int conversationId,
    required String mode,
  }) async {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final request = _pendingPersistence.remove(key);
    if (request == null) {
      return;
    }
    request.timer.cancel();
    if (_ephemeralRuntimeKeys.contains(key)) {
      return;
    }
    await persistRuntimeConversation(
      conversationId: request.conversationId,
      mode: request.mode,
      generateSummary: request.generateSummary,
      markComplete: request.markComplete,
      persistMessages: request.persistMessages,
    );
  }

  Future<void> flushAllPendingPersistence() async {
    final requests = _pendingPersistence.values.toList(growable: false);
    _pendingPersistence.clear();
    for (final request in requests) {
      request.timer.cancel();
      await persistRuntimeConversation(
        conversationId: request.conversationId,
        mode: request.mode,
        generateSummary: request.generateSummary,
        markComplete: request.markComplete,
        persistMessages: request.persistMessages,
      );
    }
  }

  String _streamingTextBatchKey(String taskId, _StreamingTextStreamKind kind) =>
      '${kind.name}:$taskId';

  _StreamingTextBatchState? _streamingTextBatchFor(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    return runtime._streamingTextBatches[_streamingTextBatchKey(taskId, kind)];
  }

  _StreamingTextBatchState _ensureStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    required String initialLatestText,
    required String initialFlushedText,
  }) {
    final key = _streamingTextBatchKey(taskId, kind);
    return runtime._streamingTextBatches.putIfAbsent(
      key,
      () => _StreamingTextBatchState(
        taskId: taskId,
        kind: kind,
        latestText: initialLatestText,
        lastFlushedText: initialFlushedText,
      ),
    );
  }

  String _streamingTextFlushTimerKey(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    return '${_runtimeKey(conversationId: runtime.conversationId, mode: runtime.mode)}:${_streamingTextBatchKey(taskId, kind)}';
  }

  void _cancelStreamingTextFlushTimer(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    final key = _streamingTextFlushTimerKey(runtime, taskId, kind);
    runtime._streamingTextFlushTimers.remove(key)?.cancel();
  }

  void _scheduleStreamingTextFlush(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    bool emitVoiceUpdates = false,
    bool schedulePersistence = false,
  }) {
    final key = _streamingTextFlushTimerKey(runtime, taskId, kind);
    runtime._streamingTextFlushTimers.remove(key)?.cancel();
    runtime._streamingTextFlushTimers[key] = Timer(
      _kStreamingTextFlushDelay,
      () {
        runtime._streamingTextFlushTimers.remove(key);
        final didFlush = _flushStreamingTextBatch(
          runtime,
          taskId,
          kind,
          emitVoiceUpdates: emitVoiceUpdates,
          schedulePersistence: schedulePersistence,
        );
        if (didFlush) {
          notifyListeners();
        }
      },
    );
  }

  void _clearStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    _cancelStreamingTextFlushTimer(runtime, taskId, kind);
    runtime._streamingTextBatches.remove(_streamingTextBatchKey(taskId, kind));
  }

  void _clearStreamingTextBatchesForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    for (final kind in _StreamingTextStreamKind.values) {
      _cancelStreamingTextFlushTimer(runtime, taskId, kind);
    }
    runtime._streamingTextBatches.removeWhere(
      (_, batch) => batch.taskId == taskId,
    );
  }

  void _flushRuntimeStreamingText(
    ChatConversationRuntimeState runtime, {
    bool emitVoiceUpdates = false,
    bool schedulePersistence = false,
  }) {
    final taskIds = runtime._streamingTextBatches.values
        .map((batch) => batch.taskId)
        .toSet()
        .toList(growable: false);
    for (final taskId in taskIds) {
      _flushStreamingTextForTask(
        runtime,
        taskId,
        emitVoiceUpdates: emitVoiceUpdates,
        schedulePersistence: schedulePersistence,
      );
    }
  }

  void _flushStreamingTextForTask(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool emitVoiceUpdates = false,
    bool schedulePersistence = false,
  }) {
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      schedulePersistence: schedulePersistence,
    );
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentThinking,
      schedulePersistence: schedulePersistence,
    );
    _flushPureChatReplyBatch(
      runtime,
      taskId,
      emitVoiceUpdate: emitVoiceUpdates,
      schedulePersistence: schedulePersistence,
    );
    _flushAgentReplyBatch(
      runtime,
      taskId,
      emitVoiceEvent: emitVoiceUpdates,
      schedulePersistence: schedulePersistence,
    );
  }

  bool _flushStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    bool emitVoiceUpdates = false,
    bool schedulePersistence = false,
  }) {
    return switch (kind) {
      _StreamingTextStreamKind.pureChatThinking => _flushThinkingBatch(
        runtime,
        taskId,
        kind,
        schedulePersistence: schedulePersistence,
      ),
      _StreamingTextStreamKind.agentThinking => _flushAgentThinkingBatchToCard(
        runtime,
        taskId,
      ),
      _StreamingTextStreamKind.pureChatReply => _flushPureChatReplyBatch(
        runtime,
        taskId,
        emitVoiceUpdate: emitVoiceUpdates,
        schedulePersistence: schedulePersistence,
      ),
      _StreamingTextStreamKind.agentReply => _flushAgentReplyBatch(
        runtime,
        taskId,
        emitVoiceEvent: emitVoiceUpdates,
        schedulePersistence: schedulePersistence,
      ),
    };
  }

  bool _stageStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    required String nextText,
    required String initialLatestText,
    required String initialFlushedText,
  }) {
    if (nextText.isEmpty) {
      return false;
    }
    final state = _ensureStreamingTextBatch(
      runtime,
      taskId,
      kind,
      initialLatestText: initialLatestText,
      initialFlushedText: initialFlushedText,
    );
    if (nextText == state.latestText) {
      return state.reachedFlushThreshold;
    }
    state.stage(nextText);
    return state.reachedFlushThreshold ||
        state.containsFlushableNewlineSinceFlush;
  }

  String _visiblePureChatReplyText(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == taskId,
    );
    if (index == -1) {
      return '';
    }
    return (runtime.messages[index].content?['text'] as String? ?? '');
  }

  String? _latestAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    String? result;
    var maxSequence = 0;
    for (final message in runtime.messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence <= maxSequence) {
        continue;
      }
      maxSequence = sequence;
      result = message.id;
    }
    return result;
  }

  String _visibleAgentReplyText(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? messageId,
  }) {
    final resolvedMessageId =
        messageId ??
        _resolvePendingAgentTextMessageId(runtime, taskId) ??
        _latestAgentTextMessageId(runtime, taskId);
    if (resolvedMessageId == null) {
      return '';
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == resolvedMessageId,
    );
    if (index == -1) {
      return '';
    }
    return (runtime.messages[index].content?['text'] as String? ?? '');
  }

  String _visibleThinkingText(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null) {
      return runtime.deepThinkingContent;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == thinkingCardId,
    );
    if (index == -1) {
      return runtime.deepThinkingContent;
    }
    return (runtime.messages[index].cardData?['thinkingContent'] as String? ??
            runtime.deepThinkingContent)
        .toString();
  }

  /// 返回已完成 Markdown 渲染的文本长度。
  ///
  /// - 无待刷新数据时返回 `null`（表示全量 Markdown 渲染）
  /// - 有待刷新数据时返回上次 flush 的文本长度，前端据此分段渲染
  int? _markdownRenderedLengthForBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    final batch = _streamingTextBatchFor(runtime, taskId, kind);
    if (batch == null || !batch.hasPendingFlush) {
      return null;
    }
    return batch.lastFlushedText.length;
  }

  bool _applyPureChatReplyUpdate(
    ChatConversationRuntimeState runtime,
    String taskId, {
    required String text,
    required bool isError,
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const <Map<String, dynamic>>[],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    bool emitVoiceUpdate = false,
    bool schedulePersistence = false,
  }) {
    final hasExistingMessage = runtime.messages.any(
      (message) => message.id == taskId,
    );
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final shouldWrite =
        isError ||
        isSummarizing ||
        text.isNotEmpty ||
        attachments.isNotEmpty ||
        (hasPerformanceMetrics && hasExistingMessage);
    if (!shouldWrite) {
      return false;
    }

    _removeLatestLoadingIfExists(runtime);
    _removeOpenClawWaitingCard(runtime, taskId);
    final reasoningContent =
        _normalizeReasoningContent(runtime.currentThinkingMessages[taskId]) ??
        _normalizeReasoningContent(runtime.deepThinkingContent);
    _updateOrAddAiMessage(
      runtime,
      taskId,
      text,
      isError,
      isSummarizing: isSummarizing,
      renderMarkdown: renderMarkdown,
      markdownRenderedLength: markdownRenderedLength,
      attachments: attachments,
      prefillTokensPerSecond: prefillTokensPerSecond,
      decodeTokensPerSecond: decodeTokensPerSecond,
      reasoningContent: reasoningContent,
    );
    if (emitVoiceUpdate &&
        !isError &&
        !isSummarizing &&
        text.trim().isNotEmpty) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
          messageId: taskId,
          text: text,
          isFinal: false,
        ),
      );
    }
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
      );
    }
    return true;
  }

  bool _flushPureChatReplyBatch(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool emitVoiceUpdate = false,
    bool schedulePersistence = false,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatReply,
    );
    if (batch == null || !batch.hasPendingFlush) {
      return false;
    }
    final visibleText = runtime.currentAiMessages[taskId] ?? batch.latestText;
    // Pass last-flushed length so the markdown widget only re-parses the new suffix,
    // giving us both batched rebuilds and incremental markdown rendering.
    final markdownRenderedLength = batch.lastFlushedText.isNotEmpty
        ? batch.lastFlushedText.length
        : null;
    batch.markFlushed();
    return _applyPureChatReplyUpdate(
      runtime,
      taskId,
      text: visibleText,
      isError: false,
      renderMarkdown: true,
      markdownRenderedLength: markdownRenderedLength,
      emitVoiceUpdate: emitVoiceUpdate,
      schedulePersistence: schedulePersistence,
    );
  }

  void _upsertAgentReplyMessage(
    ChatConversationRuntimeState runtime,
    String messageId,
    String text, {
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isFinal = false,
    bool isError = false,
    Map<String, dynamic>? streamMeta,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    String? reasoningContent,
  }) {
    final resolvedStreamMeta = ensureAgentStreamMessageMeta(
      streamMeta,
      entryId: messageId,
      isFinal: isFinal,
    );
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    if (index == -1) {
      final content = <String, dynamic>{
        'text': text,
        'id': messageId,
        'renderMarkdown': renderMarkdown,
        if (isFinal && prefillTokensPerSecond != null)
          'prefillTokensPerSecond': prefillTokensPerSecond,
        if (isFinal && decodeTokensPerSecond != null)
          'decodeTokensPerSecond': decodeTokensPerSecond,
      };
      if (markdownRenderedLength != null) {
        content['markdownRenderedLength'] = markdownRenderedLength;
      }
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: messageId,
          type: 1,
          user: 2,
          content: content,
          isError: isError,
          streamMeta: resolvedStreamMeta,
          reasoningContent: _normalizeReasoningContent(reasoningContent),
        ),
      );
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final currentText = (content['text'] ?? '').toString();
    content['text'] = text.isNotEmpty ? text : currentText;
    content['renderMarkdown'] = renderMarkdown;
    if (markdownRenderedLength != null) {
      content['markdownRenderedLength'] = markdownRenderedLength;
    } else {
      content.remove('markdownRenderedLength');
    }
    if (isFinal && prefillTokensPerSecond != null) {
      content['prefillTokensPerSecond'] = prefillTokensPerSecond;
    }
    if (isFinal && decodeTokensPerSecond != null) {
      content['decodeTokensPerSecond'] = decodeTokensPerSecond;
    }
    runtime.messages[index] = existing.copyWith(
      content: content,
      isError: isError,
      streamMeta: ensureAgentStreamMessageMeta(
        resolvedStreamMeta ?? existing.streamMeta,
        entryId: messageId,
        isFinal: isFinal,
      ),
      reasoningContent:
          _normalizeReasoningContent(reasoningContent) ??
          existing.reasoningContent,
    );
  }

  bool _flushAgentReplyBatch(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? messageId,
    bool isFinal = false,
    bool isError = false,
    bool emitVoiceEvent = false,
    bool schedulePersistence = false,
    Map<String, dynamic>? streamMeta,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentReply,
    );
    final requestedMessageId = messageId?.trim() ?? '';
    final resolvedMessageId =
        (requestedMessageId.isNotEmpty ? requestedMessageId : null) ??
        _resolvePendingAgentTextMessageId(runtime, taskId) ??
        _latestAgentTextMessageId(runtime, taskId) ??
        _nextAgentTextMessageId(runtime, taskId);
    final text =
        batch?.latestText ??
        runtime.currentAiMessages[taskId] ??
        _visibleAgentReplyText(runtime, taskId, messageId: resolvedMessageId);
    final hasPendingFlush = batch?.hasPendingFlush ?? false;
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final hasExistingMessage = runtime.messages.any(
      (message) => message.id == resolvedMessageId,
    );
    final shouldWrite =
        hasPendingFlush ||
        (text.isNotEmpty && !hasExistingMessage) ||
        (hasPerformanceMetrics && hasExistingMessage) ||
        (isFinal && hasExistingMessage);
    if (shouldWrite) {
      _upsertAgentReplyMessage(
        runtime,
        resolvedMessageId,
        text,
        renderMarkdown: true,
        isFinal: isFinal,
        isError: isError,
        streamMeta: streamMeta,
        prefillTokensPerSecond: prefillTokensPerSecond,
        decodeTokensPerSecond: decodeTokensPerSecond,
        reasoningContent: runtime.currentThinkingMessages[taskId],
      );
    }
    if (batch != null && (hasPendingFlush || isFinal)) {
      batch.markFlushed();
    }
    if (emitVoiceEvent &&
        text.trim().isNotEmpty &&
        (hasPendingFlush || isFinal)) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
          messageId: resolvedMessageId,
          text: text,
          isFinal: isFinal,
        ),
      );
    }
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
      );
    }
    if (isFinal) {
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.agentReply,
      );
    }
    return shouldWrite || (emitVoiceEvent && text.trim().isNotEmpty && isFinal);
  }

  bool _flushThinkingBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    bool schedulePersistence = false,
  }) {
    final batch = _streamingTextBatchFor(runtime, taskId, kind);
    if (batch == null || !batch.hasPendingFlush) {
      return false;
    }
    final binding =
        _taskBindings[taskId] ??
        _TaskBinding(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
        );
    final thinking =
        runtime.currentThinkingMessages[taskId] ?? batch.latestText;
    if (thinking.isNotEmpty) {
      _applyThinkingUpdate(
        runtime,
        binding,
        taskId,
        thinking,
        notifyAfterUpdate: false,
        schedulePersistence: false,
      );
    }
    batch.markFlushed();
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
    return thinking.isNotEmpty;
  }

  bool _flushAgentThinkingBatchToCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    Map<String, dynamic>? streamMeta,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentThinking,
    );
    final thinking =
        runtime.currentThinkingMessages[taskId] ??
        batch?.latestText ??
        runtime.deepThinkingContent;
    if (thinking.trim().isEmpty) {
      return false;
    }

    final requestedCardId = cardId?.trim() ?? '';
    final thinkingCardId =
        (requestedCardId.isNotEmpty ? requestedCardId : null) ??
        _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null || thinkingCardId.trim().isEmpty) {
      return false;
    }

    final index = runtime.messages.indexWhere(
      (message) => message.id == thinkingCardId,
    );
    if (index == -1) {
      return false;
    }
    final visibleThinking =
        (runtime.messages[index].cardData?['thinkingContent'] ?? '').toString();
    final shouldWrite =
        (batch?.hasPendingFlush ?? false) || visibleThinking != thinking;
    if (!shouldWrite) {
      return false;
    }

    _updateThinkingCard(
      runtime,
      taskId,
      cardId: thinkingCardId,
      thinkingContent: thinking,
      isLoading: true,
      stage: runtime.currentThinkingStage,
      streamMeta: streamMeta,
      lockCompleted: false,
    );
    batch?.markFlushed();
    return true;
  }

  void _handleChatTaskMessage(String taskId, String content, String? type) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

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
    var shouldUpdateAiMessage = false;
    // Tracks whether any change has been written to runtime.messages this call.
    // notifyListeners() is only called when didFlush is true, matching the
    // agent streaming path which guards every notify with didUpdateVisibleMessage.
    var didFlush = false;
    var didSchedulePersistence = false;

    if (isRateLimited) {
      _flushPureChatReplyBatch(runtime, taskId, emitVoiceUpdate: true);
      messageText = kRateLimitErrorMessage;
      isError = true;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      runtime.currentAiMessages.remove(taskId);
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isErrorMessage) {
      _flushPureChatReplyBatch(runtime, taskId, emitVoiceUpdate: true);
      messageText = kNetworkErrorMessage;
      isError = true;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      runtime.currentAiMessages.remove(taskId);
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isSummaryStart) {
      _flushPureChatReplyBatch(runtime, taskId, emitVoiceUpdate: true);
      messageText = '';
      isError = false;
      isSummarizing = true;
      runtime.isContextCompressing = true;
      runtime.currentAiMessages[taskId] = '';
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isOpenClawAttachment) {
      messageText =
          runtime.currentAiMessages[taskId] ??
          _visiblePureChatReplyText(runtime, taskId);
      isError = false;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      shouldUpdateAiMessage = true;
    } else {
      final thinking = extractChatTaskThinking(
        content,
        fallbackToRawText: false,
      );
      if (thinking.isNotEmpty) {
        if (_upsertPureChatThinking(runtime, taskId, thinking)) {
          didFlush = true;
        }
      }
      final text = extractChatTaskText(content, fallbackToRawText: false);
      if (text.isNotEmpty) {
        final previousText = runtime.currentAiMessages[taskId] ?? '';
        final mergedText = mergeLegacyStreamingText(previousText, text);
        if (mergedText != previousText && mergedText.isNotEmpty) {
          runtime.currentAiMessages[taskId] = mergedText;
          final visibleText = _visiblePureChatReplyText(runtime, taskId);
          // Flush immediately on the first message (no visible text yet) so the
          // loading indicator is dismissed promptly — same logic as agent path.
          final reachedFlushThreshold = _stageStreamingTextBatch(
            runtime,
            taskId,
            _StreamingTextStreamKind.pureChatReply,
            nextText: mergedText,
            initialLatestText: previousText.isNotEmpty
                ? previousText
                : visibleText,
            initialFlushedText: visibleText,
          );
          final shouldFlush =
              visibleText.trim().isEmpty || reachedFlushThreshold;
          if (shouldFlush) {
            if (_flushPureChatReplyBatch(
              runtime,
              taskId,
              emitVoiceUpdate: true,
              schedulePersistence: true,
            )) {
              didFlush = true;
              didSchedulePersistence = true;
            }
          } else {
            final batch = _streamingTextBatchFor(
              runtime,
              taskId,
              _StreamingTextStreamKind.pureChatReply,
            );
            if (_applyPureChatReplyUpdate(
              runtime,
              taskId,
              text: mergedText,
              isError: false,
              renderMarkdown: true,
              markdownRenderedLength: batch?.lastFlushedText.length,
            )) {
              _scheduleStreamingTextFlush(
                runtime,
                taskId,
                _StreamingTextStreamKind.pureChatReply,
                emitVoiceUpdates: true,
                schedulePersistence: true,
              );
            }
          }
        }
      }
      messageText = runtime.currentAiMessages[taskId] ?? '';
      isError = false;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      if (payloadAttachments.isNotEmpty || hasPerformanceMetrics) {
        shouldUpdateAiMessage = true;
      }
    }

    if (shouldUpdateAiMessage &&
        _applyPureChatReplyUpdate(
          runtime,
          taskId,
          text: messageText,
          isError: isError,
          renderMarkdown: true,
          markdownRenderedLength: _markdownRenderedLengthForBatch(
            runtime,
            taskId,
            _StreamingTextStreamKind.pureChatReply,
          ),
          isSummarizing: isSummarizing,
          attachments: payloadAttachments,
          prefillTokensPerSecond: prefillTokensPerSecond,
          decodeTokensPerSecond: decodeTokensPerSecond,
          schedulePersistence: true,
        )) {
      didFlush = true;
      didSchedulePersistence = true;
    }
    runtime.isAiResponding = true;
    // Only notify when runtime.messages actually changed — prevents rebuilding
    // the entire widget tree on every streaming token between batch flushes.
    if (didFlush) {
      notifyListeners();
    }
    if (!didSchedulePersistence &&
        (isRateLimited || isErrorMessage || isSummaryStart)) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
  }

  void _handleChatTaskMessageEnd(String taskId) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId != null) {
      runtime.currentThinkingStage = ThinkingStage.complete.value;
      runtime.isDeepThinking = false;
      _finalizeThinkingCardsForTask(runtime, taskId);
      runtime.currentThinkingMessages.remove(taskId);
      runtime.deepThinkingContent = '';
      runtime.lastAgentTaskId = null;
      runtime.activeThinkingCardId = null;
      runtime.pendingThinkingRoundSplit = false;
      runtime.thinkingRound = 0;
    }

    runtime.isAiResponding = false;
    runtime.isContextCompressing = false;
    _flushPureChatReplyBatch(runtime, taskId);
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    final isErrorMessage = index != -1 && runtime.messages[index].isError;
    final messageText = isErrorMessage
        ? (runtime.messages[index].content?['text'] as String? ?? '')
        : (runtime.currentAiMessages[taskId] ??
              _visiblePureChatReplyText(runtime, taskId));

    if (messageText.isNotEmpty && index != -1) {
      final existing = runtime.messages[index];
      runtime.messages[index] = existing.copyWith(content: existing.content);
      _syncMessageLinkPreviews(runtime, taskId);
    }
    if (!isErrorMessage && messageText.trim().isNotEmpty) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageCompleted(
          messageId: taskId,
          text: messageText,
        ),
      );
    }
    runtime.currentAiMessages.remove(taskId);
    _clearStreamingTextBatchesForTask(runtime, taskId);
    _taskBindings.remove(taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
  }

  void _handleAgentStreamEvent(AgentStreamEvent event) {
    var binding = _taskBindings[event.taskId];
    var runtime = _runtimeForTask(event.taskId);
    if (binding == null || runtime == null) {
      final recovered = _recoverExternalAgentStreamBinding(event);
      if (recovered == null) {
        return;
      }
      binding = recovered.binding;
      runtime = recovered.runtime;
    }

    final reduceResult = _agentStreamReducer.reduce(
      runtime.agentStreamStates[event.taskId],
      event,
    );
    if (!reduceResult.accepted) {
      return;
    }
    final thinkingCardToFinalize = _resolveThinkingCardToFinalize(
      reduceResult,
      event,
    );
    runtime.agentStreamStates[event.taskId] = reduceResult.nextState;
    _syncRuntimeAgentState(runtime, event, reduceResult.nextState);

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        _applyAgentThinkingStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.textSnapshot:
        _applyAgentTextStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
        _applyAgentToolStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.workbenchProjectCard:
        _applyAgentUiCardStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.clarifyRequired:
        _applyAgentClarifyStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.completed:
        _applyAgentCompletedStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.error:
        _applyAgentErrorStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.permissionRequired:
        _applyAgentPermissionStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
    }
  }

  ({_TaskBinding binding, ChatConversationRuntimeState runtime})?
  _recoverExternalAgentStreamBinding(AgentStreamEvent event) {
    final conversationId = _asPositiveInt(event.raw['conversationId']);
    if (conversationId == null) {
      return null;
    }
    final runtimeMode = _runtimeModeFromConversationMode(
      (event.raw['conversationMode'] ?? event.raw['mode'] ?? '').toString(),
    );
    final runtime = runtimeFor(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    if (runtime == null) {
      return null;
    }
    final binding = _TaskBinding(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    _taskBindings[event.taskId] = binding;
    runtime.currentDispatchTaskId ??= event.taskId;
    runtime.lastAgentTaskId = event.taskId;
    return (binding: binding, runtime: runtime);
  }

  int? _asPositiveInt(dynamic raw) {
    final value = switch (raw) {
      int value => value,
      num value => value.toInt(),
      String value => int.tryParse(value.trim()),
      _ => null,
    };
    return value != null && value > 0 ? value : null;
  }

  String _runtimeModeFromConversationMode(String rawMode) {
    return switch (ConversationMode.fromStorageValue(rawMode)) {
      ConversationMode.openclaw => kChatRuntimeModeOpenClaw,
      ConversationMode.codex => kChatRuntimeModeCodex,
      _ => kChatRuntimeModeNormal,
    };
  }

  void _syncRuntimeAgentState(
    ChatConversationRuntimeState runtime,
    AgentStreamEvent event,
    AgentStreamTaskState state,
  ) {
    runtime.currentDispatchTaskId ??= event.taskId;
    runtime.lastAgentTaskId = event.taskId;
    runtime.activeThinkingCardId = state.activeThinkingEntryId;
    runtime.currentThinkingStage = state.thinkingStage;
    runtime.isDeepThinking = state.isDeepThinking;
    runtime.thinkingRound = state.thinkingRounds.length;
    runtime.toolCardSequence = state.toolCards.length;
    runtime.pendingThinkingRoundSplit = false;
    runtime.browserSessionSnapshot =
        state.browserSnapshot ?? runtime.browserSessionSnapshot;
    runtime.pendingAgentTextTaskId =
        event.kind == AgentStreamEventKind.textSnapshot && !event.isFinal
        ? event.taskId
        : null;
    // Drive activeToolCardId from reducer's active round (supports ≥1 tools).
    final activeToolIds = state.activeToolEntryIds;
    if (activeToolIds.isNotEmpty) {
      runtime.activeToolCardId = activeToolIds.last;
    } else if (event.kind == AgentStreamEventKind.toolCompleted) {
      runtime.activeToolCardId = null;
    }
  }

  void _applyAgentThinkingStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    final streamMeta = _streamMetaFromEvent(event);
    if (event.kind == AgentStreamEventKind.thinkingStarted) {
      runtime.currentThinkingMessages[event.taskId] = '';
      runtime.deepThinkingContent = '';
      // If this is a higher-numbered entry, remove any empty first-round
      // placeholder ("$taskId-thinking-1") left by createThinkingCard.
      final basePlaceholder = '${event.taskId}-thinking-1';
      if (cardId != basePlaceholder) {
        runtime.messages.removeWhere((msg) {
          if (msg.id != basePlaceholder) return false;
          return (msg.cardData?['thinkingContent']?.toString() ?? '')
              .trim()
              .isEmpty;
        });
      }
    }
    final previousThinking =
        runtime.currentThinkingMessages[event.taskId] ?? '';
    var nextThinking = previousThinking;
    final hasThinkingContent = event.thinking.trim().isNotEmpty;
    if (hasThinkingContent) {
      nextThinking = mergeAgentTextSnapshot(previousThinking, event.thinking);
      runtime.currentThinkingMessages[event.taskId] = nextThinking;
      runtime.deepThinkingContent = nextThinking;
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    if (!hasThinkingContent && nextThinking.trim().isEmpty) {
      if (event.kind == AgentStreamEventKind.thinkingStarted) {
        final exists = runtime.messages.any((message) => message.id == cardId);
        if (exists) {
          _updateThinkingCard(
            runtime,
            event.taskId,
            cardId: cardId,
            isLoading: true,
            stage: event.stage <= 0
                ? ThinkingStage.thinking.value
                : event.stage,
            streamMeta: streamMeta,
            lockCompleted: false,
          );
        } else {
          _createThinkingCard(
            runtime,
            event.taskId,
            cardId: cardId,
            thinkingContent: '',
            isLoading: true,
            stage: event.stage <= 0
                ? ThinkingStage.thinking.value
                : event.stage,
            streamMeta: streamMeta,
          );
        }
        schedulePersistRuntimeConversation(
          conversationId: binding.conversationId,
          mode: binding.mode,
        );
      }
      notifyListeners();
      return;
    }
    final exists = runtime.messages.any((message) => message.id == cardId);
    var didUpdateVisibleCard = false;
    if (exists) {
      if (nextThinking.trim().isEmpty) {
        _updateThinkingCard(
          runtime,
          event.taskId,
          cardId: cardId,
          isLoading: true,
          stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
          streamMeta: streamMeta,
          lockCompleted: false,
        );
        didUpdateVisibleCard = true;
      } else {
        final visibleThinking = _visibleThinkingText(runtime, event.taskId);
        final reachedFlushThreshold = _stageStreamingTextBatch(
          runtime,
          event.taskId,
          _StreamingTextStreamKind.agentThinking,
          nextText: nextThinking,
          initialLatestText: previousThinking.isNotEmpty
              ? previousThinking
              : visibleThinking,
          initialFlushedText: visibleThinking,
        );
        final shouldFlush =
            visibleThinking.trim().isEmpty || reachedFlushThreshold;
        if (shouldFlush) {
          didUpdateVisibleCard = _flushAgentThinkingBatchToCard(
            runtime,
            event.taskId,
            cardId: cardId,
            streamMeta: streamMeta,
          );
        } else {
          _updateThinkingCard(
            runtime,
            event.taskId,
            cardId: cardId,
            thinkingContent: nextThinking,
            isLoading: true,
            stage: event.stage <= 0
                ? ThinkingStage.thinking.value
                : event.stage,
            streamMeta: streamMeta,
            lockCompleted: false,
          );
          _scheduleStreamingTextFlush(
            runtime,
            event.taskId,
            _StreamingTextStreamKind.agentThinking,
            schedulePersistence: true,
          );
        }
      }
    } else {
      _createThinkingCard(
        runtime,
        event.taskId,
        cardId: cardId,
        thinkingContent: nextThinking,
        isLoading: true,
        stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
        streamMeta: streamMeta,
      );
      didUpdateVisibleCard = true;
      if (nextThinking.trim().isNotEmpty) {
        _streamingTextBatchFor(
          runtime,
          event.taskId,
          _StreamingTextStreamKind.agentThinking,
        )?.markFlushed();
      }
    }
    if (!didUpdateVisibleCard) {
      return;
    }
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentTextStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isEmpty || text.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    final didFinalizeWorkbenchTools = _finalizeRunningToolCardsForTask(
      runtime,
      event.taskId,
    );
    final previousText = runtime.currentAiMessages[event.taskId] ?? '';
    final nextText = mergeAgentTextSnapshot(previousText, text);
    runtime.currentAiMessages[event.taskId] = nextText;
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    final visibleText = _visibleAgentReplyText(
      runtime,
      event.taskId,
      messageId: messageId,
    );
    final reachedFlushThreshold = _stageStreamingTextBatch(
      runtime,
      event.taskId,
      _StreamingTextStreamKind.agentReply,
      nextText: nextText,
      initialLatestText: previousText.isNotEmpty ? previousText : visibleText,
      initialFlushedText: visibleText,
    );
    final shouldFlush =
        visibleText.trim().isEmpty || event.isFinal || reachedFlushThreshold;
    var didUpdateVisibleMessage = false;
    if (shouldFlush) {
      didUpdateVisibleMessage = _flushAgentReplyBatch(
        runtime,
        event.taskId,
        messageId: messageId,
        isFinal: event.isFinal,
        emitVoiceEvent: true,
        streamMeta: _streamMetaFromEvent(event),
        prefillTokensPerSecond: event.prefillTokensPerSecond,
        decodeTokensPerSecond: event.decodeTokensPerSecond,
      );
    } else {
      final batch = _streamingTextBatchFor(
        runtime,
        event.taskId,
        _StreamingTextStreamKind.agentReply,
      );
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        nextText,
        renderMarkdown: true,
        markdownRenderedLength: batch?.lastFlushedText.length,
        isFinal: event.isFinal,
        streamMeta: _streamMetaFromEvent(event),
        prefillTokensPerSecond: event.prefillTokensPerSecond,
        decodeTokensPerSecond: event.decodeTokensPerSecond,
      );
      _scheduleStreamingTextFlush(
        runtime,
        event.taskId,
        _StreamingTextStreamKind.agentReply,
        emitVoiceUpdates: true,
        schedulePersistence: true,
      );
    }
    if (event.isFinal) {
      _syncMessageLinkPreviews(runtime, messageId);
    }
    if (!didUpdateVisibleMessage) {
      if (didFinalizeWorkbenchTools) {
        notifyListeners();
        schedulePersistRuntimeConversation(
          conversationId: binding.conversationId,
          mode: binding.mode,
        );
      }
      return;
    }
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentToolStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final toolEvent = AgentToolEventData.fromMap(event.raw);
    final cardId = resolveAgentToolCardId(event, raw: event.raw);
    if (cardId.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    _updateToolLayerState(runtime, toolEvent);
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _upsertToolCard(
      runtime: runtime,
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
    if (event.kind == AgentStreamEventKind.toolCompleted) {
      if (toolEvent.toolType == 'workbench' ||
          toolEvent.toolName.startsWith('workbench_')) {
        _finalizeRunningToolCardsForTask(runtime, event.taskId);
      }
      _updateBrowserSessionSnapshot(runtime, toolEvent);
      if (event.browserSnapshot != null) {
        runtime.browserSessionSnapshot = event.browserSnapshot;
      }
    }
    _scheduleToolEventFlush(
      runtime,
      immediate: event.kind != AgentStreamEventKind.toolProgress,
    );
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentClarifyStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final text = event.question.trim().isNotEmpty
        ? event.question.trim()
        : event.text.trim();
    final messageId = (event.entryId ?? '').trim();

    // When the clarify event carries a structured dialog, inject a dialog
    // card message. The text message is omitted — the card's own message field
    // is the question.
    if (event.dialog != null) {
      final cardId =
          '${messageId.isNotEmpty ? messageId : event.taskId}-dialog';
      final cardData = <String, dynamic>{
        'type': 'user_dialog',
        'dialogData': {
          'type': event.dialog!.type,
          'message': event.dialog!.message,
          if (event.dialog!.title != null) 'title': event.dialog!.title,
          if (event.dialog!.confirmLabel != null)
            'confirmLabel': event.dialog!.confirmLabel,
          if (event.dialog!.cancelLabel != null)
            'cancelLabel': event.dialog!.cancelLabel,
          if (event.dialog!.danger) 'danger': true,
          if (event.dialog!.choices.isNotEmpty)
            'choices': event.dialog!.choices
                .map(
                  (c) => {
                    'label': c.label,
                    'value': c.value,
                    if (c.hint != null) 'hint': c.hint,
                  },
                )
                .toList(),
          if (event.dialog!.placeholder != null)
            'placeholder': event.dialog!.placeholder,
          if (event.dialog!.inputType != null)
            'inputType': event.dialog!.inputType,
        },
      };
      final cardIndex = runtime.messages.indexWhere((m) => m.id == cardId);
      final cardMessage = ChatMessageModel(
        id: cardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': cardId},
        // Intentionally no streamMeta: user_dialog must be a standalone entry
        // in the timeline (not absorbed into an agent-run group), so it always
        // renders at the visual bottom of the chat list.
      );
      if (cardIndex == -1) {
        runtime.messages.insert(0, cardMessage);
      } else {
        runtime.messages[cardIndex] = cardMessage;
      }
    } else if (messageId.isNotEmpty && text.isNotEmpty) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        reasoningContent: event.thinking,
      );
    }
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeRunningToolCardsForTask(
      runtime,
      event.taskId,
      status: 'interrupted',
      summary: AppTextLocalizer.choose(
        en: 'Waiting for more detail',
        zh: '等待补充信息',
      ),
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentCompletedStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _flushAgentReplyBatch(
      runtime,
      event.taskId,
      messageId: event.entryId,
      isFinal: true,
      emitVoiceEvent: true,
      streamMeta: _streamMetaFromEvent(event),
      prefillTokensPerSecond: event.prefillTokensPerSecond,
      decodeTokensPerSecond: event.decodeTokensPerSecond,
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeRunningToolCardsForTask(runtime, event.taskId);
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentErrorStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final shouldMarkError = event.raw['persistAsError'] == true;
    if (entryId.isNotEmpty && shouldMarkError) {
      final index = runtime.messages.indexWhere(
        (message) => message.id == entryId,
      );
      if (index != -1) {
        runtime.messages[index] = runtime.messages[index].copyWith(
          isError: true,
        );
      }
    }
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _flushAgentReplyBatch(
      runtime,
      event.taskId,
      messageId: entryId,
      isFinal: true,
      isError: shouldMarkError,
      emitVoiceEvent: true,
      streamMeta: _streamMetaFromEvent(event),
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeRunningToolCardsForTask(
      runtime,
      event.taskId,
      status: 'error',
      summary: event.errorMessage.trim().isEmpty
          ? AppTextLocalizer.choose(en: 'Workbench step failed', zh: '工作台步骤失败')
          : event.errorMessage.trim(),
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentPermissionStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isNotEmpty && text.isNotEmpty) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        reasoningContent: event.thinking,
      );
    }
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeRunningToolCardsForTask(
      runtime,
      event.taskId,
      status: 'interrupted',
      summary: AppTextLocalizer.choose(
        en: 'Waiting for permission',
        zh: '等待权限确认',
      ),
    );

    final executionPermissionIds = event.missingPermissions
        .map((item) => item.trim())
        .map((item) => _executionPermissionNameToId[item])
        .whereType<String>()
        .toSet()
        .toList(growable: false);
    final permissionCardId =
        (event.raw['permissionCardId'] ?? '${event.taskId}-permission')
            .toString();
    if (executionPermissionIds.isNotEmpty) {
      final cardIndex = runtime.messages.indexWhere(
        (message) => message.id == permissionCardId,
      );
      final cardData = <String, dynamic>{
        'type': 'permission_section',
        'requiredPermissionIds': executionPermissionIds,
      };
      final message = ChatMessageModel(
        id: permissionCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': permissionCardId},
        streamMeta: _streamMetaFromEvent(event),
      );
      if (cardIndex == -1) {
        runtime.messages.insert(0, message);
      } else {
        runtime.messages[cardIndex] = runtime.messages[cardIndex].copyWith(
          content: {'cardData': cardData, 'id': permissionCardId},
          streamMeta: _streamMetaFromEvent(event),
        );
      }
    }

    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  Map<String, dynamic> _streamMetaFromEvent(AgentStreamEvent event) {
    return buildAgentStreamMetaFromEvent(event);
  }

  /// Returns true if thinking content was flushed to the card (caller must notifyListeners).
  bool _upsertPureChatThinking(
    ChatConversationRuntimeState runtime,
    String taskId,
    String thinking,
  ) {
    final binding = _taskBindings[taskId];
    if (binding == null) return false;

    final previous = runtime.currentThinkingMessages[taskId] ?? '';
    final merged = mergeLegacyStreamingText(previous, thinking);
    if (merged.isEmpty || merged == previous) return false;

    runtime.currentThinkingMessages[taskId] = merged;
    if (runtime.thinkingRound == 0) {
      primePureChatThinking(
        taskId: taskId,
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
      _applyThinkingUpdate(
        runtime,
        binding,
        taskId,
        merged,
        notifyAfterUpdate: false,
        schedulePersistence: false,
      );
      return true;
    }
    final visibleThinking = _visibleThinkingText(runtime, taskId);
    final shouldFlush = _stageStreamingTextBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      nextText: merged,
      initialLatestText: previous.isNotEmpty ? previous : visibleThinking,
      initialFlushedText: visibleThinking,
    );
    if (shouldFlush) {
      return _flushThinkingBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatThinking,
        schedulePersistence: true,
      );
    }
    _applyThinkingUpdate(
      runtime,
      binding,
      taskId,
      merged,
      notifyAfterUpdate: false,
      schedulePersistence: false,
    );
    _scheduleStreamingTextFlush(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      schedulePersistence: true,
    );
    return false;
  }

  void _applyThinkingUpdate(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    String taskId,
    String thinking, {
    bool notifyAfterUpdate = true,
    bool schedulePersistence = true,
  }) {
    if (runtime.pendingThinkingRoundSplit) {
      if (thinking.trim().isEmpty) {
        return;
      }

      final previousThinkingCardId = _resolveThinkingCardId(runtime, taskId);
      if (previousThinkingCardId != null) {
        _updateThinkingCard(
          runtime,
          taskId,
          cardId: previousThinkingCardId,
          isLoading: false,
          stage: ThinkingStage.complete.value,
          lockCompleted: false,
        );
      }

      runtime.thinkingRound += 1;
      runtime.activeThinkingCardId =
          '$taskId-thinking-${runtime.thinkingRound}';
      _createThinkingCard(
        runtime,
        taskId,
        cardId: runtime.activeThinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: ThinkingStage.thinking.value,
      );
      runtime.deepThinkingContent = thinking;
      runtime.pendingThinkingRoundSplit = false;
      if (notifyAfterUpdate) {
        notifyListeners();
      }
      return;
    }

    runtime.deepThinkingContent = thinking;
    runtime.lastAgentTaskId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.isDeepThinking = true;
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null) {
      runtime.activeThinkingCardId = _baseThinkingCardId(taskId);
      _createThinkingCard(
        runtime,
        taskId,
        cardId: runtime.activeThinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: runtime.currentThinkingStage,
      );
    } else {
      _updateThinkingCard(
        runtime,
        taskId,
        cardId: thinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: runtime.currentThinkingStage,
        lockCompleted: false,
      );
    }
    if (notifyAfterUpdate) {
      notifyListeners();
    }
  }

  void _handleAgentContextCompactionStateChanged(
    String taskId,
    bool isCompacting,
    int? latestPromptTokens,
    int? promptTokenThreshold,
  ) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    if (isCompacting) {
      beginContextCompaction(
        conversationId: binding.conversationId,
        mode: binding.mode,
        taskId: taskId,
        trigger: 'auto',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    } else {
      finishContextCompaction(
        conversationId: binding.conversationId,
        mode: binding.mode,
        status: 'completed',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    }
  }

  void _handleAgentPromptTokenUsageChanged(
    String taskId,
    int latestPromptTokens,
    int? promptTokenThreshold,
  ) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void beginContextCompaction({
    required int conversationId,
    required String mode,
    String? taskId,
    String trigger = 'auto',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    runtime.isContextCompressing = true;
    final activeMarkerId = runtime.activeContextCompactionMarkerId;
    final markerId =
        activeMarkerId != null &&
            runtime.messages.any((message) => message.id == activeMarkerId)
        ? activeMarkerId
        : _buildContextCompactionMarkerId(
            conversationId: conversationId,
            taskId: taskId,
            trigger: trigger,
          );
    runtime.activeContextCompactionMarkerId = markerId;
    _upsertContextCompactionMarker(
      runtime,
      markerId: markerId,
      status: 'compressing',
      trigger: trigger,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void finishContextCompaction({
    required int conversationId,
    required String mode,
    String status = 'completed',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    runtime.isContextCompressing = false;
    final markerId = runtime.activeContextCompactionMarkerId;
    if (markerId != null) {
      _upsertContextCompactionMarker(
        runtime,
        markerId: markerId,
        status: status,
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    }
    runtime.activeContextCompactionMarkerId = null;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void _applyPromptTokenUsageUpdate(
    ChatConversationRuntimeState runtime, {
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final conversation = runtime.conversation;
    if (conversation == null ||
        (latestPromptTokens == null && promptTokenThreshold == null)) {
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    runtime.conversation = conversation.copyWith(
      latestPromptTokens: latestPromptTokens ?? conversation.latestPromptTokens,
      promptTokenThreshold:
          promptTokenThreshold ?? conversation.promptTokenThreshold,
      latestPromptTokensUpdatedAt: latestPromptTokens != null
          ? now
          : conversation.latestPromptTokensUpdatedAt,
    );
  }

  void _handleVlmTaskFinish(String? taskId) {
    if (taskId == null || taskId.isEmpty) return;
    final binding = _taskBindings[taskId];
    if (binding == null) return;
    final runtime = _runtimeForTask(taskId);
    if (runtime == null) return;
    runtime.isExecutingTask = false;
    runtime.isInputAreaVisible = true;
    runtime.vlmInfoQuestion = null;
    runtime.isSubmittingVlmReply = false;
    _taskBindings.remove(taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        generateSummary: true,
        markComplete: true,
      ),
    );
  }

  void _handleVlmRequestUserInput(String question, String? taskId) {
    if (taskId == null || taskId.isEmpty) return;
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;
    runtime.vlmInfoQuestion = question;
    runtime.isSubmittingVlmReply = false;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  ChatConversationRuntimeState? _runtimeForTask(String taskId) {
    final binding = _taskBindings[taskId];
    if (binding == null) return null;
    return ensureRuntime(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _removeLatestLoadingIfExists(ChatConversationRuntimeState runtime) {
    if (runtime.messages.isNotEmpty && runtime.messages[0].isLoading) {
      runtime.messages.removeAt(0);
    }
  }

  void _updateOrAddAiMessage(
    ChatConversationRuntimeState runtime,
    String taskId,
    String text,
    bool isError, {
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const [],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    String? reasoningContent,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      final content = <String, dynamic>{
        'text': text,
        'id': taskId,
        'renderMarkdown': renderMarkdown,
      };
      if (markdownRenderedLength != null) {
        content['markdownRenderedLength'] = markdownRenderedLength;
      } else {
        content.remove('markdownRenderedLength');
      }
      if (prefillTokensPerSecond != null) {
        content['prefillTokensPerSecond'] = prefillTokensPerSecond;
      }
      if (decodeTokensPerSecond != null) {
        content['decodeTokensPerSecond'] = decodeTokensPerSecond;
      }
      if (attachments.isNotEmpty) {
        content['attachments'] = attachments;
      }
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: taskId,
          type: 1,
          user: 2,
          content: content,
          isLoading: false,
          isError: isError,
          isSummarizing: isSummarizing,
          reasoningContent: _normalizeReasoningContent(reasoningContent),
        ),
      );
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? {});
    final existingText = content['text'] as String? ?? '';
    content['text'] = text.isNotEmpty ? text : existingText;
    content['renderMarkdown'] = renderMarkdown;
    if (markdownRenderedLength != null) {
      content['markdownRenderedLength'] = markdownRenderedLength;
    } else {
      content.remove('markdownRenderedLength');
    }
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
    runtime.messages[index] = existing.copyWith(
      content: content,
      isLoading: false,
      isError: isError,
      isSummarizing: isSummarizing,
      reasoningContent:
          _normalizeReasoningContent(reasoningContent) ??
          existing.reasoningContent,
    );
  }

  // 将 AI 文本消息里的 URL 同步成 content.linkPreviews，UI 只负责展示该字段。
  void _syncMessageLinkPreviews(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = runtime.messages[index];
    if (message.type != 1 ||
        message.user != 2 ||
        message.isLoading ||
        message.isError ||
        message.isSummarizing) {
      return;
    }

    final content = Map<String, dynamic>.from(message.content ?? const {});
    final nextPreviews = LinkPreviewService.instance.reconcilePreviewMaps(
      text: message.text ?? '',
      existing: content['linkPreviews'],
    );
    final currentPreviews = content['linkPreviews'];
    var didUpdate = false;
    if (!_previewMapListsEqual(currentPreviews, nextPreviews)) {
      if (nextPreviews.isEmpty) {
        content.remove('linkPreviews');
      } else {
        content['linkPreviews'] = nextPreviews;
      }
      runtime.messages[index] = message.copyWith(content: content);
      didUpdate = true;
    }
    if (didUpdate &&
        nextPreviews.any(
          (item) =>
              ChatLinkPreview.fromJson(item).status !=
              ChatLinkPreview.statusLoading,
        )) {
      unawaited(
        ConversationHistoryService.saveConversationMessages(
          runtime.conversationId,
          List<ChatMessageModel>.from(runtime.messages),
          mode: _conversationModeFromRuntimeMode(
            runtime.mode,
            conversation: runtime.conversation,
          ),
        ),
      );
    }

    // 先写 loading 占位，真实网页信息抓取完成后再局部回填。
    for (final previewMap in nextPreviews) {
      final preview = ChatLinkPreview.fromJson(previewMap);
      if (preview.status != ChatLinkPreview.statusLoading ||
          preview.url.isEmpty) {
        continue;
      }
      unawaited(
        _resolveMessageLinkPreview(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
          taskId: taskId,
          url: preview.url,
        ),
      );
    }
  }

  Future<void> _resolveMessageLinkPreview({
    required int conversationId,
    required String mode,
    required String taskId,
    required String url,
  }) async {
    final resolved = await LinkPreviewService.instance.loadPreview(url);
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) {
      return;
    }
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = runtime.messages[index];
    final content = Map<String, dynamic>.from(message.content ?? const {});
    final rawPreviews = content['linkPreviews'];
    if (rawPreviews is! List) {
      return;
    }

    // 只替换仍处于 loading 的同一 URL，避免覆盖历史 ready/failed 结果。
    var changed = false;
    final updatedPreviews = rawPreviews
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .map((previewMap) {
          final preview = ChatLinkPreview.fromJson(previewMap);
          if (preview.url != url ||
              preview.status != ChatLinkPreview.statusLoading) {
            return previewMap;
          }
          changed = true;
          return resolved.toJson();
        })
        .toList();
    if (!changed) {
      return;
    }

    content['linkPreviews'] = updatedPreviews;
    runtime.messages[index] = message.copyWith(content: content);
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
    await ConversationHistoryService.saveConversationMessages(
      conversationId,
      List<ChatMessageModel>.from(runtime.messages),
      mode: _conversationModeFromRuntimeMode(
        mode,
        conversation: runtime.conversation,
      ),
    );
  }

  bool _previewMapListsEqual(dynamic left, List<Map<String, dynamic>> right) {
    if (left is! List) {
      return right.isEmpty;
    }
    final normalizedLeft = left
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
    if (normalizedLeft.length != right.length) {
      return false;
    }
    for (var index = 0; index < normalizedLeft.length; index += 1) {
      if (!_previewMapEquals(normalizedLeft[index], right[index])) {
        return false;
      }
    }
    return true;
  }

  bool _previewMapEquals(
    Map<String, dynamic> left,
    Map<String, dynamic> right,
  ) {
    return left['url'] == right['url'] &&
        left['domain'] == right['domain'] &&
        left['siteName'] == right['siteName'] &&
        left['title'] == right['title'] &&
        left['description'] == right['description'] &&
        left['imageUrl'] == right['imageUrl'] &&
        left['status'] == right['status'];
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

    void addAll(List<Map<String, dynamic>> source) {
      for (final item in source) {
        final key = _attachmentIdentity(item);
        if (!seen.add(key)) continue;
        merged.add(item);
      }
    }

    addAll(previous);
    addAll(latest);
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

  void _createThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    final loadingIndex = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (loadingIndex != -1) {
      runtime.messages.removeAt(loadingIndex);
    }

    final startTime = DateTime.now().millisecondsSinceEpoch;
    final thinkingCardId = cardId ?? '$taskId-thinking';

    // If creating a card for round 2+ (e.g. "$taskId-thinking-2"), remove any
    // empty first-round placeholder ("$taskId-thinking-1") left by createThinkingCard.
    final basePlaceholderId = '$taskId-thinking-1';
    if (thinkingCardId != basePlaceholderId) {
      runtime.messages.removeWhere((msg) {
        if (msg.id != basePlaceholderId) return false;
        return (msg.cardData?['thinkingContent']?.toString() ?? '')
            .trim()
            .isEmpty;
      });
    }

    final cardData = {
      'type': 'deep_thinking',
      'isLoading': isLoading ?? runtime.isDeepThinking,
      'thinkingContent': thinkingContent ?? '',
      'stage': stage ?? runtime.currentThinkingStage,
      'taskID': taskId,
      'cardId': thinkingCardId,
      'startTime': startTime,
      'endTime': null,
      'isCollapsible': true,
    };

    runtime.messages.removeWhere((msg) => msg.id == thinkingCardId);
    runtime.messages.insert(
      0,
      ChatMessageModel(
        id: thinkingCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': thinkingCardId},
        createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
        streamMeta: ensureAgentStreamMessageMeta(
          streamMeta,
          entryId: thinkingCardId,
        ),
      ),
    );
  }

  String _buildContextCompactionMarkerId({
    required int conversationId,
    String? taskId,
    required String trigger,
  }) {
    final suffix = DateTime.now().millisecondsSinceEpoch;
    final normalizedTaskId = taskId?.trim();
    if (normalizedTaskId != null && normalizedTaskId.isNotEmpty) {
      return '$normalizedTaskId-context-compaction-$suffix';
    }
    return 'conversation-$conversationId-$trigger-context-compaction-$suffix';
  }

  void _upsertContextCompactionMarker(
    ChatConversationRuntimeState runtime, {
    required String markerId,
    required String status,
    String trigger = 'auto',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == markerId);
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = Map<String, dynamic>.from(
      existing?.cardData ?? const <String, dynamic>{},
    );
    final startTime =
        (existingCardData['startTime'] as int?) ??
        DateTime.now().millisecondsSinceEpoch;
    final endTime = status == 'compressing'
        ? null
        : DateTime.now().millisecondsSinceEpoch;
    final resolvedTriggerRaw = (existingCardData['trigger'] ?? trigger)
        .toString()
        .trim();
    final resolvedTrigger = resolvedTriggerRaw.isEmpty
        ? trigger
        : resolvedTriggerRaw;
    final cardData = <String, dynamic>{
      'type': 'context_compaction_marker',
      'status': status,
      'label': _contextCompactionLabel(status),
      'trigger': resolvedTrigger,
      'startTime': startTime,
      'endTime': endTime,
      'latestPromptTokens':
          latestPromptTokens ?? runtime.conversation?.latestPromptTokens,
      'promptTokenThreshold':
          promptTokenThreshold ?? runtime.conversation?.promptTokenThreshold,
    };
    final message = ChatMessageModel(
      id: markerId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': markerId},
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': markerId},
      );
    }
    _persistContextCompactionMarkerIfNeeded(
      conversationId: runtime.conversationId,
      mode: runtime.mode,
      message: index == -1 ? message : runtime.messages[index],
    );
  }

  String _contextCompactionLabel(String status) {
    return switch (status) {
      'compressing' => AppTextLocalizer.choose(en: 'Compressing', zh: '正在压缩'),
      'noop' => AppTextLocalizer.choose(en: 'No compaction needed', zh: '无需压缩'),
      'failed' => AppTextLocalizer.choose(en: 'Compaction failed', zh: '压缩失败'),
      _ => AppTextLocalizer.choose(en: 'Compacted', zh: '已压缩'),
    };
  }

  void _persistContextCompactionMarkerIfNeeded({
    required int conversationId,
    required String mode,
    required ChatMessageModel message,
  }) {
    final cardData = message.cardData;
    if (message.type != 2 || cardData?['type'] != 'context_compaction_marker') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: Map<String, dynamic>.from(cardData!),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: _conversationModeFromRuntimeMode(
          mode,
          conversation: runtimeFor(
            conversationId: conversationId,
            mode: mode,
          )?.conversation,
        ),
      ),
    );
  }

  void _updateThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    final thinkingCardId = cardId ?? '$taskId-thinking';
    final index = runtime.messages.indexWhere(
      (msg) => msg.id == thinkingCardId,
    );
    if (index == -1) return;

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? {});
    final cardData = Map<String, dynamic>.from(content['cardData'] ?? {});

    final currentStage = cardData['stage'] as int? ?? 1;
    final targetStage = stage ?? runtime.currentThinkingStage;
    final newStage = (lockCompleted && currentStage == 4) ? 4 : targetStage;

    final startTime = cardData['startTime'] as int?;
    int? endTime = cardData['endTime'] as int?;
    if (newStage == 4 && endTime == null) {
      endTime = DateTime.now().millisecondsSinceEpoch;
    } else if (newStage != 4 && newStage != 5) {
      endTime = null;
    }

    cardData['thinkingContent'] =
        thinkingContent ?? runtime.deepThinkingContent;
    cardData['isLoading'] = isLoading ?? runtime.isDeepThinking;
    cardData['stage'] = newStage;
    cardData['taskID'] = taskId;
    cardData['cardId'] = thinkingCardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] = endTime;

    content['cardData'] = cardData;
    runtime.messages[index] = existing.copyWith(
      content: content,
      streamMeta: ensureAgentStreamMessageMeta(
        streamMeta ?? existing.streamMeta,
        entryId: thinkingCardId,
      ),
    );
  }

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

  void _finalizeThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
  }) {
    final thinkingCardId = (cardId ?? '').trim();
    if (taskId.trim().isEmpty || thinkingCardId.isEmpty) {
      return;
    }
    final index = runtime.messages.indexWhere(
      (msg) => msg.id == thinkingCardId,
    );
    if (index == -1) {
      return;
    }

    final existing = runtime.messages[index];
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

    final storedContent = (cardData['thinkingContent']?.toString() ?? '')
        .trim();
    final runtimeContent = runtime.deepThinkingContent.trim();
    cardData['thinkingContent'] = storedContent.isNotEmpty
        ? storedContent
        : runtimeContent;
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.complete.value;
    cardData['taskID'] = taskId;
    cardData['cardId'] = thinkingCardId;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    content['cardData'] = cardData;
    runtime.messages[index] = existing.copyWith(content: content);
  }

  void _persistDeepThinkingCardIfNeeded({
    required int conversationId,
    required String mode,
    required ChatMessageModel message,
  }) {
    final cardData = message.cardData;
    if (message.type != 2 || cardData?['type'] != 'deep_thinking') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: buildPersistentDeepThinkingCardData(
          Map<String, dynamic>.from(cardData!),
        ),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: _conversationModeFromRuntimeMode(
          mode,
          conversation: runtimeFor(
            conversationId: conversationId,
            mode: mode,
          )?.conversation,
        ),
      ),
    );
  }

  void _finalizeThinkingCardsForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final endTime = DateTime.now().millisecondsSinceEpoch;
    var touched = false;
    for (var index = 0; index < runtime.messages.length; index++) {
      final message = runtime.messages[index];
      final cardData = message.cardData;
      if (message.type != 2 || cardData?['type'] != 'deep_thinking') {
        continue;
      }
      if ((cardData?['taskID'] ?? '').toString().trim() != taskId) {
        continue;
      }

      final content = Map<String, dynamic>.from(message.content ?? const {});
      final mutableCardData = Map<String, dynamic>.from(cardData ?? const {});
      final currentStageRaw = mutableCardData['stage'];
      final currentStage = currentStageRaw is num
          ? currentStageRaw.toInt()
          : int.tryParse(currentStageRaw?.toString() ?? '');
      final isLoading = mutableCardData['isLoading'] == true;
      if (!isLoading && currentStage == ThinkingStage.complete.value) {
        continue;
      }

      final storedContent =
          (mutableCardData['thinkingContent']?.toString() ?? '').trim();
      if (storedContent.isEmpty) {
        runtime.messages.removeAt(index);
        index--;
        touched = true;
        continue;
      }

      mutableCardData['isLoading'] = false;
      mutableCardData['stage'] = ThinkingStage.complete.value;
      mutableCardData['endTime'] ??= endTime;
      content['cardData'] = mutableCardData;
      runtime.messages[index] = message.copyWith(content: content);
      _persistDeepThinkingCardIfNeeded(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
        message: runtime.messages[index],
      );
      touched = true;
    }
    if (touched) {
      runtime.activeThinkingCardId = null;
      runtime.pendingThinkingRoundSplit = false;
    }
  }

  String _baseThinkingCardId(String taskId) => '$taskId-thinking';

  String? _resolveThinkingCardId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    if (runtime.activeThinkingCardId != null) {
      return runtime.activeThinkingCardId;
    }
    final baseId = _baseThinkingCardId(taskId);
    final exists = runtime.messages.any((msg) => msg.id == baseId);
    return exists ? baseId : null;
  }

  String? _resolvePendingAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    if (runtime.pendingAgentTextTaskId != taskId) return null;
    for (final message in runtime.messages) {
      if (_isAgentTextMessageForTask(message, taskId)) {
        return message.id;
      }
    }
    return null;
  }

  String _nextAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final baseId = _agentTextBaseId(taskId);
    var maxSequence = 0;
    for (final message in runtime.messages) {
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

  void _upsertToolCard({
    required ChatConversationRuntimeState runtime,
    required AgentStreamEvent streamEvent,
    required String defaultRunningSummary,
    Map<String, dynamic>? streamMeta,
  }) {
    final projection = AgentToolCardProjection.projectStreamEvent(
      event: streamEvent,
      messages: runtime.messages,
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
      runtime.messages.insert(
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
      runtime.messages[projection.existingIndex] = runtime
          .messages[projection.existingIndex]
          .copyWith(
            id: projection.cardId,
            content: {'cardData': projection.cardData, 'id': projection.cardId},
            streamMeta: projection.streamMeta,
          );
    }
  }

  void _applyAgentUiCardStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cards = extractAgentStreamUiCards(event);
    if (cards.isEmpty) {
      return;
    }
    runtime.isAiResponding = true;
    _flushAgentThinkingBatchToCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    for (final card in cards) {
      _upsertAgentUiCard(runtime: runtime, event: event, card: card);
    }
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _upsertAgentUiCard({
    required ChatConversationRuntimeState runtime,
    required AgentStreamEvent event,
    required AgentStreamUiCard card,
  }) {
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: card.id,
    );
    final index = runtime.messages.indexWhere(
      (message) =>
          message.id == card.id ||
          (message.cardData?['cardId'] ?? '').toString().trim() == card.id,
    );
    if (index == -1) {
      runtime.messages.insert(
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
      runtime.messages[index].cardData ?? const <String, dynamic>{},
    );
    runtime.messages[index] = runtime.messages[index].copyWith(
      content: {
        'cardData': <String, dynamic>{...existingCardData, ...card.cardData},
        'id': card.id,
      },
      streamMeta: streamMeta,
    );
  }

  void updateChatIslandDisplayLayer({
    required int conversationId,
    required String mode,
    required ChatIslandDisplayLayer layer,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null || runtime.chatIslandDisplayLayer == layer) {
      return;
    }
    runtime.chatIslandDisplayLayer = layer;
    notifyListeners();
  }

  void _updateToolLayerState(
    ChatConversationRuntimeState runtime,
    AgentToolEventData event,
  ) {
    final toolType = event.toolType.trim();
    if (toolType != 'terminal' && toolType != 'browser') {
      return;
    }
    runtime.lastAgentToolType = toolType;
    runtime.chatIslandDisplayLayer = ChatIslandDisplayLayer.tools;
  }

  void _updateBrowserSessionSnapshot(
    ChatConversationRuntimeState runtime,
    AgentToolEventData event,
  ) {
    if (event.toolType.trim() != 'browser') {
      return;
    }
    final workspaceId = (event.workspaceId ?? '').trim();
    if (!event.success || workspaceId.isEmpty) {
      return;
    }
    final snapshot =
        ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
          rawJson: event.rawResultJson,
          workspaceId: workspaceId,
        ) ??
        ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
          rawJson: event.resultPreviewJson,
          workspaceId: workspaceId,
        );
    if (snapshot == null) {
      return;
    }
    runtime.browserSessionSnapshot = snapshot;
  }

  void _scheduleToolEventFlush(
    ChatConversationRuntimeState runtime, {
    required bool immediate,
  }) {
    final key = _runtimeKey(
      conversationId: runtime.conversationId,
      mode: runtime.mode,
    );
    runtime.toolEventFlushTimers[key]?.cancel();
    runtime.toolEventFlushTimers.remove(key);
    if (immediate) {
      notifyListeners();
      return;
    }
    runtime.toolEventFlushTimers[key] = Timer(_toolEventFlushDelay, () {
      runtime.toolEventFlushTimers.remove(key);
      notifyListeners();
    });
  }

  String? _normalizeReasoningContent(String? value) {
    final normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  void _removeOpenClawWaitingCard(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final waitingCardId = '$taskId-openclaw-waiting';
    runtime.messages.removeWhere((msg) => msg.id == waitingCardId);
  }

  String _buildConversationHistoryText(List<ChatMessageModel> messages) {
    final buffer = StringBuffer();
    for (final message in messages) {
      if (message.user != 1) continue;
      final text = message.content?['text'] as String? ?? '';
      if (text.isEmpty) continue;
      buffer.write(
        AppTextLocalizer.choose(en: 'User: $text\n', zh: '用户: $text\n'),
      );
    }
    return buffer.toString().trim();
  }

  ConversationMode _conversationModeFromRuntimeMode(
    String mode, {
    ConversationModel? conversation,
  }) {
    return mode == kChatRuntimeModeOpenClaw
        ? ConversationMode.openclaw
        : mode == kChatRuntimeModeCodex
        ? ConversationMode.codex
        : switch (conversation?.mode) {
            ConversationMode.chatOnly => ConversationMode.chatOnly,
            ConversationMode.subagent => ConversationMode.subagent,
            _ => ConversationMode.normal,
          };
  }

  void _cancelPendingPersistence({
    required int conversationId,
    required String mode,
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final request = _pendingPersistence.remove(key);
    request?.timer.cancel();
  }

  String _runtimeKey({required int conversationId, required String mode}) {
    return '$mode:$conversationId';
  }
}
