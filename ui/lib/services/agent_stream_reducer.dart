import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

class AgentStreamTaskState {
  const AgentStreamTaskState({
    required this.taskId,
    this.lastSeq = 0,
    this.thinkingRounds = const <String, int>{},
    this.assistantSegments = const <String, int>{},
    this.toolCards = const <String, int>{},
    this.activeThinkingEntryId,
    this.activeAssistantEntryId,
    this.phase = AgentStreamPhase.idle,
    this.thinkingStage = 1,
    this.isDeepThinking = false,
    this.browserSnapshot,
  });

  final String taskId;
  final int lastSeq;
  final Map<String, int> thinkingRounds;
  final Map<String, int> assistantSegments;
  final Map<String, int> toolCards;
  final String? activeThinkingEntryId;
  final String? activeAssistantEntryId;
  final AgentStreamPhase phase;
  final int thinkingStage;
  final bool isDeepThinking;
  final ChatBrowserSessionSnapshot? browserSnapshot;

  AgentStreamTaskState copyWith({
    int? lastSeq,
    Map<String, int>? thinkingRounds,
    Map<String, int>? assistantSegments,
    Map<String, int>? toolCards,
    String? activeThinkingEntryId,
    bool clearActiveThinkingEntryId = false,
    String? activeAssistantEntryId,
    bool clearActiveAssistantEntryId = false,
    AgentStreamPhase? phase,
    int? thinkingStage,
    bool? isDeepThinking,
    ChatBrowserSessionSnapshot? browserSnapshot,
    bool clearBrowserSnapshot = false,
  }) {
    return AgentStreamTaskState(
      taskId: taskId,
      lastSeq: lastSeq ?? this.lastSeq,
      thinkingRounds: thinkingRounds ?? this.thinkingRounds,
      assistantSegments: assistantSegments ?? this.assistantSegments,
      toolCards: toolCards ?? this.toolCards,
      activeThinkingEntryId: clearActiveThinkingEntryId
          ? null
          : (activeThinkingEntryId ?? this.activeThinkingEntryId),
      activeAssistantEntryId: clearActiveAssistantEntryId
          ? null
          : (activeAssistantEntryId ?? this.activeAssistantEntryId),
      phase: phase ?? this.phase,
      thinkingStage: thinkingStage ?? this.thinkingStage,
      isDeepThinking: isDeepThinking ?? this.isDeepThinking,
      browserSnapshot: clearBrowserSnapshot
          ? null
          : (browserSnapshot ?? this.browserSnapshot),
    );
  }
}

class AgentStreamReduceResult {
  const AgentStreamReduceResult({
    required this.accepted,
    required this.previousState,
    required this.nextState,
    this.previousThinkingEntryId,
    this.previousAssistantEntryId,
    this.isNewThinkingEntry = false,
    this.isNewAssistantEntry = false,
  });

  final bool accepted;
  final AgentStreamTaskState previousState;
  final AgentStreamTaskState nextState;
  final String? previousThinkingEntryId;
  final String? previousAssistantEntryId;
  final bool isNewThinkingEntry;
  final bool isNewAssistantEntry;
}

class AgentStreamReducer {
  const AgentStreamReducer();

  AgentStreamReduceResult reduce(
    AgentStreamTaskState? current,
    AgentStreamEvent event,
  ) {
    final previousState = current ?? AgentStreamTaskState(taskId: event.taskId);
    if (event.seq <= previousState.lastSeq) {
      return AgentStreamReduceResult(
        accepted: false,
        previousState: previousState,
        nextState: previousState,
      );
    }

    final previousThinkingEntryId = previousState.activeThinkingEntryId;
    final previousAssistantEntryId = previousState.activeAssistantEntryId;
    final thinkingRounds = Map<String, int>.from(previousState.thinkingRounds);
    final assistantSegments = Map<String, int>.from(
      previousState.assistantSegments,
    );
    final toolCards = Map<String, int>.from(previousState.toolCards);

    var phase = previousState.phase;
    var thinkingStage = previousState.thinkingStage;
    var isDeepThinking = previousState.isDeepThinking;
    var activeThinkingEntryId = previousThinkingEntryId;
    var activeAssistantEntryId = previousAssistantEntryId;
    var browserSnapshot = previousState.browserSnapshot;
    var isNewThinkingEntry = false;
    var isNewAssistantEntry = false;

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        phase = AgentStreamPhase.thinking;
        thinkingStage = event.stage <= 0 ? 1 : event.stage;
        isDeepThinking = true;
        if (event.entryId != null && event.entryId!.trim().isNotEmpty) {
          activeThinkingEntryId = event.entryId!.trim();
          final roundIndex = event.roundIndex <= 0 ? 1 : event.roundIndex;
          isNewThinkingEntry =
              activeThinkingEntryId != previousThinkingEntryId &&
              !thinkingRounds.containsKey(activeThinkingEntryId);
          thinkingRounds[activeThinkingEntryId] = roundIndex;
        }
        break;
      case AgentStreamEventKind.textSnapshot:
        phase = AgentStreamPhase.output;
        if (event.entryId != null && event.entryId!.trim().isNotEmpty) {
          activeAssistantEntryId = event.entryId!.trim();
          final roundIndex = event.roundIndex <= 0 ? 1 : event.roundIndex;
          isNewAssistantEntry =
              activeAssistantEntryId != previousAssistantEntryId &&
              !assistantSegments.containsKey(activeAssistantEntryId);
          assistantSegments[activeAssistantEntryId] = roundIndex;
        }
        break;
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
        phase = AgentStreamPhase.tool;
        thinkingStage = 2;
        if (event.entryId != null && event.entryId!.trim().isNotEmpty) {
          toolCards[event.entryId!.trim()] = event.roundIndex;
        }
        browserSnapshot = event.browserSnapshot ?? browserSnapshot;
        break;
      case AgentStreamEventKind.completed:
        phase = AgentStreamPhase.completed;
        thinkingStage = 4;
        isDeepThinking = false;
        break;
      case AgentStreamEventKind.error:
        phase = AgentStreamPhase.error;
        thinkingStage = 4;
        isDeepThinking = false;
        break;
      case AgentStreamEventKind.clarifyRequired:
        phase = AgentStreamPhase.clarify;
        thinkingStage = 4;
        isDeepThinking = false;
        break;
      case AgentStreamEventKind.permissionRequired:
        phase = AgentStreamPhase.permissionRequired;
        thinkingStage = 4;
        isDeepThinking = false;
        break;
    }

    final nextState = previousState.copyWith(
      lastSeq: event.seq,
      thinkingRounds: thinkingRounds,
      assistantSegments: assistantSegments,
      toolCards: toolCards,
      activeThinkingEntryId: activeThinkingEntryId,
      activeAssistantEntryId: activeAssistantEntryId,
      phase: phase,
      thinkingStage: thinkingStage,
      isDeepThinking: isDeepThinking,
      browserSnapshot: browserSnapshot,
    );
    return AgentStreamReduceResult(
      accepted: true,
      previousState: previousState,
      nextState: nextState,
      previousThinkingEntryId: previousThinkingEntryId,
      previousAssistantEntryId: previousAssistantEntryId,
      isNewThinkingEntry: isNewThinkingEntry,
      isNewAssistantEntry: isNewAssistantEntry,
    );
  }
}
