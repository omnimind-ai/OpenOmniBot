import 'package:ui/features/home/pages/chat/tool_activity_utils.dart'
    show isStaleAgentToolPreviewPlaceholder;
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_card_policy.dart';

export 'package:ui/services/agent_tool_card_policy.dart'
    show AgentToolActivityKind;

const String kAgentToolActivityCardType = kAgentToolSummaryCardType;

class AgentProcessItem {
  const AgentProcessItem.message(this.message) : activity = null;

  const AgentProcessItem.activity(this.activity) : message = null;

  final ChatMessageModel? message;
  final AgentToolActivity? activity;

  bool get isActivity => activity != null;
}

class AgentToolActivity {
  const AgentToolActivity({
    required this.id,
    required this.kind,
    required this.title,
    required this.status,
    required this.taskId,
    required this.messages,
    required this.steps,
  });

  final String id;
  final AgentToolActivityKind kind;
  final String title;
  final String status;
  final String taskId;
  final List<ChatMessageModel> messages;
  final List<AgentToolActivityStep> steps;

  int get stepCount => steps.length;

  bool get isRunning => status == 'running';
}

class AgentToolActivityStep {
  const AgentToolActivityStep({
    required this.cardId,
    required this.title,
    required this.action,
    required this.target,
    required this.status,
    required this.isRetry,
    required this.isCurrent,
    required this.message,
  });

  final String cardId;
  final String title;
  final String action;
  final String target;
  final String status;
  final bool isRetry;

  /// True for the last in-progress step while the activity is still running.
  final bool isCurrent;
  final ChatMessageModel message;
}

/// Stateful wrapper around [compactAgentProcessItems].
///
/// Own one instance per [State] that renders a process section. The input list
/// is small, and stream updates often replace a card in place, so rebuild the
/// compacted transcript whenever the visible message signature changes.
class AgentActivityCompactor {
  int? _lastSignature;
  final List<AgentProcessItem> _committed = [];
  _ActivityBuilder? _pending;
  List<AgentProcessItem> _cachedResult = const [];

  List<AgentProcessItem> compact(
    List<ChatMessageModel> messages, {
    bool settleRunning = false,
  }) {
    if (messages.isEmpty) {
      _reset();
      return const [];
    }

    final signature = Object.hash(settleRunning, _messagesSignature(messages));
    if (signature == _lastSignature) {
      return _cachedResult;
    }

    _committed.clear();
    _pending = null;
    for (final message in messages) {
      _processOne(message, settleRunning: settleRunning);
    }
    _lastSignature = signature;
    _cachedResult = _buildResult();
    return _cachedResult;
  }

  void _reset() {
    _lastSignature = null;
    _committed.clear();
    _pending = null;
    _cachedResult = const [];
  }

  void _processOne(ChatMessageModel message, {required bool settleRunning}) {
    if (isStaleAgentToolPreviewPlaceholder(message)) return;

    final candidate = _ToolActivityCandidate.tryParse(
      message,
      settleRunning: settleRunning,
    );
    if (candidate == null) {
      _flushPending();
      _committed.add(AgentProcessItem.message(message));
      return;
    }

    final builder = _pending;
    if (builder != null && builder.canAppend(candidate)) {
      builder.append(candidate);
      return;
    }

    _flushPending();
    _pending = _ActivityBuilder(candidate);
  }

  void _flushPending() {
    final builder = _pending;
    if (builder == null) return;
    _committed.add(builder.buildItem());
    _pending = null;
  }

  List<AgentProcessItem> _buildResult() {
    final builder = _pending;
    if (builder == null) return List.unmodifiable(_committed);
    return [..._committed, builder.buildItem()];
  }

  int _messagesSignature(List<ChatMessageModel> messages) {
    return Object.hashAll(messages.map(_messageSignature));
  }

  int _messageSignature(ChatMessageModel message) {
    final cardData = message.cardData;
    return Object.hashAll(<Object?>[
      message.id,
      message.text,
      message.isLoading,
      message.isError,
      message.reasoningContent,
      message.streamMeta?['seq'],
      message.streamMeta?['kind'],
      message.streamMeta?['isFinal'],
      if (cardData != null) ...<Object?>[
        cardData['type'],
        cardData['cardId'],
        cardData['status'],
        cardData['stage'],
        cardData['isLoading'],
        cardData['thinkingContent'],
        cardData['toolType'],
        cardData['toolName'],
        cardData['toolTitle'],
        cardData['displayName'],
        cardData['summary'],
        cardData['progress'],
        cardData['argsJson'],
        cardData['args'],
        cardData['resultPreviewJson'],
        cardData['rawResultJson'],
        cardData['terminalOutput'],
      ],
    ]);
  }
}

/// Convenience wrapper for tests and one-off calls.
/// Production code should use [AgentActivityCompactor] directly.
List<AgentProcessItem> compactAgentProcessItems(
  List<ChatMessageModel> processMessages,
) => AgentActivityCompactor().compact(processMessages);

class _ActivityBuilder {
  _ActivityBuilder(_ToolActivityCandidate first)
    : activityKey = first.activityKey,
      kind = first.kind,
      taskId = first.taskId {
    append(first);
  }

  final String activityKey;
  final AgentToolActivityKind kind;
  final String taskId;

  // Incremental coalescing state: O(1) per append.
  // cardId → latest candidate for that tool call (started→progress×N→completed).
  final Map<String, _ToolActivityCandidate> _latestByCardId = {};
  final List<String> _cardIdOrder = [];
  int _syntheticKeyCounter = 0;

  // Memoised output — cleared on every append so the next buildItem() recomputes.
  AgentProcessItem? _cached;

  // All raw messages kept for AgentToolActivity.messages (RunLog correlation).
  final List<ChatMessageModel> _allMessages = [];

  bool canAppend(_ToolActivityCandidate candidate) =>
      candidate.activityKey == activityKey;

  void append(_ToolActivityCandidate candidate) {
    final id = candidate.cardId.isEmpty
        ? '__${_syntheticKeyCounter++}'
        : candidate.cardId;
    if (!_latestByCardId.containsKey(id)) {
      _cardIdOrder.add(id);
    }
    _latestByCardId[id] = candidate;
    _allMessages.add(candidate.message);
    _cached = null; // invalidate
  }

  AgentProcessItem buildItem() => _cached ??= _buildItem();

  AgentProcessItem _buildItem() {
    final rawDeduped = _cardIdOrder
        .map((id) => _latestByCardId[id]!)
        .toList(growable: false);
    final hasDetailedVlmSteps =
        kind == AgentToolActivityKind.vlm && rawDeduped.any(_isDetailedVlmStep);
    final deduped = hasDetailedVlmSteps
        ? rawDeduped
              .where((candidate) => !_isVlmTaskWrapper(candidate))
              .toList(growable: false)
        : rawDeduped;
    final displayCandidates = deduped.isEmpty ? rawDeduped : deduped;

    int lastRunningIdx = -1;
    for (var i = displayCandidates.length - 1; i >= 0; i--) {
      if (displayCandidates[i].status == 'running') {
        lastRunningIdx = i;
        break;
      }
    }

    final steps = <AgentToolActivityStep>[];
    String previousActionKey = '';
    String previousStatus = '';
    for (var i = 0; i < displayCandidates.length; i++) {
      final c = displayCandidates[i];
      final actionKey = c.actionKey;
      final isRetry =
          actionKey.isNotEmpty &&
          previousActionKey == actionKey &&
          AgentToolCardPolicy.isFailureStatus(previousStatus);
      final isCurrent = i == lastRunningIdx;
      steps.add(
        c.toStep(
          isRetry: isRetry,
          isCurrent: isCurrent,
          displayTitle: _stepDisplayTitle(c, isCurrent: isCurrent),
        ),
      );
      previousActionKey = actionKey;
      previousStatus = c.status;
    }

    final id = AgentToolCardPolicy.firstNonBlank(<Object?>[
      displayCandidates.first.taskId,
      displayCandidates.first.cardId,
      activityKey,
    ]);
    final resolvedStatus = _resolveActivityStatus(rawDeduped);
    return AgentProcessItem.activity(
      AgentToolActivity(
        id: '$id-${kind.name}-activity',
        kind: kind,
        title: _activityTitle(kind, displayCandidates, resolvedStatus),
        status: resolvedStatus,
        taskId: taskId,
        messages: List.unmodifiable(_allMessages),
        steps: steps,
      ),
    );
  }
}

bool _isDetailedVlmStep(_ToolActivityCandidate candidate) {
  if (candidate.kind != AgentToolActivityKind.vlm) {
    return false;
  }
  final spanKind = AgentToolCardPolicy.firstNonBlank(<Object?>[
    candidate.cardData['spanKind'],
    candidate.cardData['span_kind'],
  ]).toLowerCase();
  if (spanKind == 'vlm_step') {
    return true;
  }
  final compileKind = AgentToolCardPolicy.firstNonBlank(<Object?>[
    candidate.cardData['compile_kind'],
    candidate.cardData['compileKind'],
  ]).toLowerCase();
  if (compileKind == 'vlm_step') {
    return true;
  }
  return candidate.cardData['toolName']?.toString().trim() != 'vlm_task';
}

bool _isVlmTaskWrapper(_ToolActivityCandidate candidate) {
  if (candidate.kind != AgentToolActivityKind.vlm) {
    return false;
  }
  final spanKind = AgentToolCardPolicy.firstNonBlank(<Object?>[
    candidate.cardData['spanKind'],
    candidate.cardData['span_kind'],
  ]).toLowerCase();
  if (spanKind == 'vlm_step') {
    return false;
  }
  final toolName = candidate.cardData['toolName']?.toString().trim() ?? '';
  if (toolName != 'vlm_task') {
    return false;
  }
  final compileKind = AgentToolCardPolicy.firstNonBlank(<Object?>[
    candidate.cardData['compile_kind'],
    candidate.cardData['compileKind'],
  ]).toLowerCase();
  return compileKind != 'vlm_step';
}

class _ToolActivityCandidate {
  const _ToolActivityCandidate({
    required this.message,
    required this.cardData,
    required this.kind,
    required this.activityKey,
    required this.actionKey,
    required this.taskId,
    required this.cardId,
    required this.title,
    required this.action,
    required this.target,
    required this.status,
    required this.args,
  });

  final ChatMessageModel message;
  final Map<String, dynamic> cardData;
  final AgentToolActivityKind kind;
  final String activityKey;
  final String actionKey;
  final String taskId;
  final String cardId;
  final String title;
  final String action;
  final String target;
  final String status;
  final Map<String, dynamic> args;

  static _ToolActivityCandidate? tryParse(
    ChatMessageModel message, {
    bool settleRunning = false,
  }) {
    final cardData = message.cardData;
    if (cardData == null ||
        (cardData['type'] ?? '').toString() != kAgentToolActivityCardType) {
      return null;
    }

    final kind = AgentToolCardPolicy.activityKindFor(cardData);
    if (kind == null) {
      return null;
    }

    final args = AgentToolCardPolicy.decodeJsonMap(
      AgentToolCardPolicy.firstNonBlank(<Object?>[
        cardData['argsJson'],
        cardData['args'],
      ]),
    );
    final taskId =
        AgentToolCardPolicy.taskIdForMessage(message, cardData: cardData) ?? '';
    if (taskId.isEmpty) {
      return null;
    }

    final cardId = AgentToolCardPolicy.identityFromCard(
      cardData,
      message: message,
    ).primaryId;
    final activityKey = AgentToolCardPolicy.activityKeyFor(
      kind,
      taskId,
      cardData,
      args,
    );
    if (activityKey.isEmpty) {
      return null;
    }

    final action = AgentToolCardPolicy.actionFor(kind, cardData, args);
    final target = AgentToolCardPolicy.targetFor(kind, cardData, args, action);
    final title = AgentToolCardPolicy.firstNonBlank(<Object?>[
      cardData['toolTitle'],
      cardData['tool_title'],
      args['tool_title'],
      cardData['summary'],
      cardData['progress'],
      cardData['displayName'],
      cardData['toolName'],
    ]);
    final parsedStatus = AgentToolCardPolicy.normalizeStatus(
      cardData['status'],
    );
    final status = settleRunning && parsedStatus == 'running'
        ? 'success'
        : parsedStatus;

    return _ToolActivityCandidate(
      message: message,
      cardData: cardData,
      kind: kind,
      activityKey: activityKey,
      actionKey: AgentToolCardPolicy.actionKeyFor(
        kind,
        cardData,
        args,
        action,
        target,
      ),
      taskId: taskId,
      cardId: cardId,
      title: title.isEmpty
          ? AgentToolCardPolicy.activityKindLabel(kind)
          : title,
      action: action,
      target: target,
      status: status,
      args: args,
    );
  }

  AgentToolActivityStep toStep({
    required bool isRetry,
    bool isCurrent = false,
    String? displayTitle,
  }) {
    return AgentToolActivityStep(
      cardId: cardId,
      title: displayTitle ?? title,
      action: action,
      target: target,
      status: status,
      isRetry: isRetry,
      isCurrent: isCurrent,
      message: message,
    );
  }
}

String _activityTitle(
  AgentToolActivityKind kind,
  List<_ToolActivityCandidate> deduped,
  String resolvedStatus,
) {
  // When running: show the current step's target so the user can see progress.
  // When done: fall back to the kind label (step count is shown separately).
  if (resolvedStatus == 'running') {
    final current = deduped.lastWhere(
      (c) => c.status == 'running',
      orElse: () => deduped.last,
    );
    final target = _stepDisplayTitle(current, isCurrent: true).trim();
    if (target.isNotEmpty) return target;
  }
  if (deduped.length == 1) {
    final t = _stepDisplayTitle(deduped.single, isCurrent: false).trim();
    if (t.isNotEmpty) return t;
  }
  return AgentToolCardPolicy.activityKindLabel(kind);
}

String _stepDisplayTitle(
  _ToolActivityCandidate candidate, {
  required bool isCurrent,
}) {
  return AgentToolCardPolicy.semanticTitleForStep(
    kind: candidate.kind,
    title: candidate.title,
    action: candidate.action,
    target: candidate.target,
    cardData: candidate.cardData,
    args: candidate.args,
    isCurrent: isCurrent,
  );
}

String _resolveActivityStatus(List<_ToolActivityCandidate> candidates) {
  var hasRunning = false;
  var hasInterrupted = false;
  var hasError = false;
  var allSuccess = true;
  for (final c in candidates) {
    final s = c.status;
    if (s == 'running') hasRunning = true;
    if (s == 'interrupted') hasInterrupted = true;
    if (AgentToolCardPolicy.isFailureStatus(s)) hasError = true;
    if (s != 'success') allSuccess = false;
  }
  if (hasRunning) return 'running';
  final finalStatus = candidates.last.status;
  if (finalStatus == 'success' ||
      finalStatus == 'interrupted' ||
      AgentToolCardPolicy.isFailureStatus(finalStatus)) {
    return AgentToolCardPolicy.isFailureStatus(finalStatus)
        ? 'error'
        : finalStatus;
  }
  if (hasInterrupted) return 'interrupted';
  if (hasError) return 'error';
  if (allSuccess) return 'success';
  return finalStatus;
}
