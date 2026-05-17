import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/agent_tool_card_policy.dart';

class AgentStreamTaskState {
  const AgentStreamTaskState({
    required this.taskId,
    this.lastSeq = 0,
    this.thinkingRounds = const <String, int>{},
    this.assistantSegments = const <String, int>{},
    this.toolCards = const <String, int>{},
    this.activeToolEntryIds = const <String>{},
    this.activeToolFallbackEntryIds = const <String>{},
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
  final Set<String> activeToolEntryIds;
  final Set<String> activeToolFallbackEntryIds;
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
    Set<String>? activeToolEntryIds,
    Set<String>? activeToolFallbackEntryIds,
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
      activeToolEntryIds: activeToolEntryIds ?? this.activeToolEntryIds,
      activeToolFallbackEntryIds:
          activeToolFallbackEntryIds ?? this.activeToolFallbackEntryIds,
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
    final previousState = current?.taskId == event.taskId
        ? current!
        : AgentStreamTaskState(taskId: event.taskId);
    if (event.seq <= previousState.lastSeq) {
      return AgentStreamReduceResult(
        accepted: false,
        previousState: previousState,
        nextState: previousState,
      );
    }

    final previousThinkingEntryId = previousState.activeThinkingEntryId;
    final previousAssistantEntryId = previousState.activeAssistantEntryId;

    // Lazily copied only in the branch that mutates them.
    Map<String, int>? thinkingRounds;
    Map<String, int>? assistantSegments;
    Map<String, int>? toolCards;
    Set<String>? activeToolEntryIds;
    Set<String>? activeToolFallbackEntryIds;

    var phase = previousState.phase;
    var thinkingStage = previousState.thinkingStage;
    var isDeepThinking = previousState.isDeepThinking;
    var activeThinkingEntryId = previousThinkingEntryId;
    var activeAssistantEntryId = previousAssistantEntryId;
    var browserSnapshot = previousState.browserSnapshot;
    var isNewThinkingEntry = false;
    var isNewAssistantEntry = false;
    var clearActiveThinkingEntryId = false;

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        phase = AgentStreamPhase.thinking;
        thinkingStage = event.stage <= 0 ? 1 : event.stage;
        isDeepThinking = true;
        if (event.entryId != null && event.entryId!.trim().isNotEmpty) {
          activeThinkingEntryId = event.entryId!.trim();
          final roundIndex = event.roundIndex <= 0 ? 1 : event.roundIndex;
          thinkingRounds = Map<String, int>.from(previousState.thinkingRounds);
          isNewThinkingEntry =
              activeThinkingEntryId != previousThinkingEntryId &&
              !thinkingRounds.containsKey(activeThinkingEntryId);
          thinkingRounds[activeThinkingEntryId] = roundIndex;
        }
        break;
      case AgentStreamEventKind.textSnapshot:
        phase = AgentStreamPhase.output;
        thinkingStage = 4;
        isDeepThinking = false;
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        if (event.entryId != null && event.entryId!.trim().isNotEmpty) {
          activeAssistantEntryId = event.entryId!.trim();
          final roundIndex = event.roundIndex <= 0 ? 1 : event.roundIndex;
          assistantSegments = Map<String, int>.from(
            previousState.assistantSegments,
          );
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
        isDeepThinking = false;
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        final entryId = resolveAgentToolCardId(event, raw: event.raw);
        if (entryId.isNotEmpty) {
          toolCards = Map<String, int>.from(previousState.toolCards);
          activeToolEntryIds = Set<String>.from(
            previousState.activeToolEntryIds,
          );
          activeToolFallbackEntryIds = Set<String>.from(
            previousState.activeToolFallbackEntryIds,
          );
          toolCards[entryId] = event.roundIndex;
          final stableOperationId = AgentToolCardPolicy.operationIdFromEvent(
            event,
            raw: event.raw,
          );
          final eventEntryId = event.entryId?.trim() ?? '';
          if (event.kind == AgentStreamEventKind.toolCompleted) {
            activeToolEntryIds.remove(entryId);
            activeToolFallbackEntryIds.remove(entryId);
            if (eventEntryId.isNotEmpty) {
              activeToolEntryIds.remove(eventEntryId);
              activeToolFallbackEntryIds.remove(eventEntryId);
            }
            if (stableOperationId.isNotEmpty) {
              final fallbackId = _removeUnambiguousFallbackToolAlias(
                activeToolEntryIds,
                activeToolFallbackEntryIds,
              );
              if (fallbackId.isNotEmpty && fallbackId != entryId) {
                toolCards.remove(fallbackId);
              }
            }
          } else {
            activeToolEntryIds.add(entryId);
            if (stableOperationId.isEmpty) {
              activeToolFallbackEntryIds.add(entryId);
            } else {
              activeToolFallbackEntryIds.remove(entryId);
              if (event.kind != AgentStreamEventKind.toolStarted) {
                final fallbackId = _removeUnambiguousFallbackToolAlias(
                  activeToolEntryIds,
                  activeToolFallbackEntryIds,
                );
                if (fallbackId.isNotEmpty && fallbackId != entryId) {
                  toolCards.remove(fallbackId);
                }
              }
            }
          }
        }
        browserSnapshot = event.browserSnapshot ?? browserSnapshot;
        break;
      case AgentStreamEventKind.workbenchProjectCard:
        // Display-injection event only. The workbench tool calls that produced
        // this card have already gone through toolStarted/toolCompleted and
        // activeToolEntryIds is already empty. Don't change phase here or the
        // overlay capsule will show phase=tool with zero active tools.
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        break;
      case AgentStreamEventKind.completed:
        phase = AgentStreamPhase.completed;
        thinkingStage = 4;
        isDeepThinking = false;
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        activeToolEntryIds = const <String>{};
        activeToolFallbackEntryIds = const <String>{};
        break;
      case AgentStreamEventKind.error:
        phase = AgentStreamPhase.error;
        thinkingStage = 4;
        isDeepThinking = false;
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        activeToolEntryIds = const <String>{};
        activeToolFallbackEntryIds = const <String>{};
        break;
      case AgentStreamEventKind.clarifyRequired:
        phase = AgentStreamPhase.clarify;
        thinkingStage = 4;
        isDeepThinking = false;
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        activeToolEntryIds = const <String>{};
        activeToolFallbackEntryIds = const <String>{};
        break;
      case AgentStreamEventKind.permissionRequired:
        phase = AgentStreamPhase.permissionRequired;
        thinkingStage = 4;
        isDeepThinking = false;
        clearActiveThinkingEntryId = true;
        activeThinkingEntryId = null;
        activeToolEntryIds = const <String>{};
        activeToolFallbackEntryIds = const <String>{};
        break;
    }

    final nextState = previousState.copyWith(
      lastSeq: event.seq,
      thinkingRounds: thinkingRounds,
      assistantSegments: assistantSegments,
      toolCards: toolCards,
      activeToolEntryIds: activeToolEntryIds,
      activeToolFallbackEntryIds: activeToolFallbackEntryIds,
      activeThinkingEntryId: activeThinkingEntryId,
      clearActiveThinkingEntryId: clearActiveThinkingEntryId,
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

  String _removeUnambiguousFallbackToolAlias(
    Set<String> activeToolEntryIds,
    Set<String> activeToolFallbackEntryIds,
  ) {
    if (activeToolFallbackEntryIds.length != 1) {
      return '';
    }
    final fallbackId = activeToolFallbackEntryIds.single;
    activeToolFallbackEntryIds.remove(fallbackId);
    activeToolEntryIds.remove(fallbackId);
    return fallbackId;
  }
}
