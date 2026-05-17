import 'dart:convert';

import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/models/chat_message_model.dart';

const String kAgentToolActivityCardType = 'agent_tool_summary';

enum AgentToolActivityKind {
  browser,
  research,
  terminal,
  workspace,
  workbench,
  mcp,
}

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

/// Stateful wrapper around [compactAgentProcessItems] that skips recomputation
/// when the message list has not grown since the last call (append-only).
/// Own one instance per StatefulWidget that renders a process section.
/// Incremental activity compactor — processes each message exactly once.
///
/// Owns one per [State] that renders a process section. On each call to
/// [compact], only messages past [_processedCount] are visited; committed
/// (flushed) items are never recomputed. The pending [_ActivityBuilder] for
/// the current in-progress group caches its own output and only rebuilds
/// when new candidates arrive, giving O(1) amortised cost per event.
class AgentActivityCompactor {
  String? _firstMessageId;
  int _processedCount = 0;
  final List<AgentProcessItem> _committed = [];
  _ActivityBuilder? _pending;
  List<AgentProcessItem> _cachedResult = const [];

  List<AgentProcessItem> compact(List<ChatMessageModel> messages) {
    if (messages.isEmpty) {
      _reset();
      return const [];
    }

    // Detect list replacement (widget reused for a different group/task).
    final firstId = messages.first.id;
    if (firstId != _firstMessageId) {
      _reset();
      _firstMessageId = firstId;
    }

    if (messages.length == _processedCount) return _cachedResult;

    // Defensive: list shrank — shouldn't happen with append-only data.
    if (messages.length < _processedCount) {
      _reset();
      _firstMessageId = messages.first.id;
    }

    for (var i = _processedCount; i < messages.length; i++) {
      _processOne(messages[i]);
    }
    _processedCount = messages.length;
    _cachedResult = _buildResult();
    return _cachedResult;
  }

  void _reset() {
    _firstMessageId = null;
    _processedCount = 0;
    _committed.clear();
    _pending = null;
    _cachedResult = const [];
  }

  void _processOne(ChatMessageModel message) {
    if (isStaleAgentToolPreviewPlaceholder(message)) return;

    final candidate = _ToolActivityCandidate.tryParse(message);
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
}

/// Convenience wrapper for tests and one-off calls.
/// Production code should use [AgentActivityCompactor] directly.
List<AgentProcessItem> compactAgentProcessItems(
  List<ChatMessageModel> processMessages,
) =>
    AgentActivityCompactor().compact(processMessages);

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
    final deduped = _cardIdOrder
        .map((id) => _latestByCardId[id]!)
        .toList(growable: false);

    int lastRunningIdx = -1;
    for (var i = deduped.length - 1; i >= 0; i--) {
      if (deduped[i].status == 'running') {
        lastRunningIdx = i;
        break;
      }
    }

    final steps = <AgentToolActivityStep>[];
    String previousActionKey = '';
    String previousStatus = '';
    for (var i = 0; i < deduped.length; i++) {
      final c = deduped[i];
      final actionKey = c.actionKey;
      final isRetry =
          actionKey.isNotEmpty &&
          previousActionKey == actionKey &&
          _isFailureStatus(previousStatus);
      steps.add(c.toStep(isRetry: isRetry, isCurrent: i == lastRunningIdx));
      previousActionKey = actionKey;
      previousStatus = c.status;
    }

    final id = _firstNonBlank(<Object?>[
      deduped.first.taskId,
      deduped.first.cardId,
      activityKey,
    ]);
    final resolvedStatus = _resolveActivityStatus(deduped);
    return AgentProcessItem.activity(
      AgentToolActivity(
        id: '$id-${kind.name}-activity',
        kind: kind,
        title: _activityTitle(kind, deduped, resolvedStatus),
        status: resolvedStatus,
        taskId: taskId,
        messages: List.unmodifiable(_allMessages),
        steps: steps,
      ),
    );
  }
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

  static _ToolActivityCandidate? tryParse(ChatMessageModel message) {
    final cardData = message.cardData;
    if (cardData == null ||
        (cardData['type'] ?? '').toString() != kAgentToolActivityCardType) {
      return null;
    }

    final kind = _resolveActivityKind(cardData);
    if (kind == null) {
      return null;
    }

    final args = _decodeJsonMap(
      _firstNonBlank(<Object?>[cardData['argsJson'], cardData['args']]),
    );
    final taskId = _firstNonBlank(<Object?>[
      cardData['taskId'],
      cardData['taskID'],
      message.streamMeta?['parentTaskId'],
    ]);
    if (taskId.isEmpty) {
      return null;
    }

    final cardId = _firstNonBlank(<Object?>[
      cardData['cardId'],
      cardData['toolCallId'],
      cardData['tool_call_id'],
      message.contentId,
      message.id,
    ]);
    final activityKey = _activityKey(kind, taskId, cardData, args);
    if (activityKey.isEmpty) {
      return null;
    }

    final action = _resolveAction(kind, cardData, args);
    final target = _resolveTarget(kind, cardData, args, action);
    final title = _firstNonBlank(<Object?>[
      cardData['toolTitle'],
      cardData['tool_title'],
      args['tool_title'],
      cardData['summary'],
      cardData['progress'],
      cardData['displayName'],
      cardData['toolName'],
    ]);
    final status = _normalizeStatus(cardData['status']);

    return _ToolActivityCandidate(
      message: message,
      cardData: cardData,
      kind: kind,
      activityKey: activityKey,
      actionKey: _actionKey(kind, cardData, args, action, target),
      taskId: taskId,
      cardId: cardId,
      title: title.isEmpty ? _activityKindLabel(kind) : title,
      action: action,
      target: target,
      status: status,
    );
  }

  AgentToolActivityStep toStep({required bool isRetry, bool isCurrent = false}) {
    return AgentToolActivityStep(
      cardId: cardId,
      title: title,
      action: action,
      target: target,
      status: status,
      isRetry: isRetry,
      isCurrent: isCurrent,
      message: message,
    );
  }
}

AgentToolActivityKind? _resolveActivityKind(Map<String, dynamic> cardData) {
  final toolType = (cardData['toolType'] ?? '').toString().trim();
  final toolName = (cardData['toolName'] ?? '').toString().trim();

  if (toolType == 'browser' || toolName == 'browser_use') {
    return AgentToolActivityKind.browser;
  }
  if (toolType == 'research' ||
      toolName == 'web_search' ||
      toolName == 'vlm_task' ||
      toolName == 'image_picker') {
    return AgentToolActivityKind.research;
  }
  if (toolType == 'terminal' || toolName.startsWith('terminal_')) {
    return AgentToolActivityKind.terminal;
  }
  if (toolType == 'workspace' || toolName.startsWith('file_')) {
    return AgentToolActivityKind.workspace;
  }
  if (toolType == 'workbench' || toolName.startsWith('workbench_')) {
    return AgentToolActivityKind.workbench;
  }
  if (toolType == 'mcp') {
    return AgentToolActivityKind.mcp;
  }
  return null;
}

String _activityKey(
  AgentToolActivityKind kind,
  String taskId,
  Map<String, dynamic> cardData,
  Map<String, dynamic> args,
) {
  switch (kind) {
    case AgentToolActivityKind.browser:
      return '$taskId|browser';
    case AgentToolActivityKind.research:
      return '$taskId|research';
    case AgentToolActivityKind.terminal:
      final sessionId = _firstNonBlank(<Object?>[
        cardData['terminalSessionId'],
        cardData['terminal_session_id'],
        args['terminalSessionId'],
        args['terminal_session_id'],
      ]);
      return sessionId.isEmpty
          ? '$taskId|terminal'
          : '$taskId|terminal|$sessionId';
    case AgentToolActivityKind.workspace:
      return '$taskId|workspace';
    case AgentToolActivityKind.workbench:
      return '$taskId|workbench';
    case AgentToolActivityKind.mcp:
      final serverName = _firstNonBlank(<Object?>[
        cardData['serverName'],
        cardData['server_name'],
      ]);
      return serverName.isEmpty ? '$taskId|mcp' : '$taskId|mcp|$serverName';
  }
}

String _resolveAction(
  AgentToolActivityKind kind,
  Map<String, dynamic> cardData,
  Map<String, dynamic> args,
) {
  final explicit = _firstNonBlank(<Object?>[
    args['action'],
    args['operation'],
    args['command_name'],
    cardData['action'],
  ]);
  if (explicit.isNotEmpty) {
    return explicit;
  }
  final toolName = (cardData['toolName'] ?? '').toString().trim();
  switch (kind) {
    case AgentToolActivityKind.browser:
      return toolName == 'browser_use' ? 'browser' : toolName;
    case AgentToolActivityKind.research:
      return toolName == 'web_search' ? 'search' : toolName;
    case AgentToolActivityKind.terminal:
      return toolName.startsWith('terminal_')
          ? toolName.substring('terminal_'.length)
          : toolName;
    case AgentToolActivityKind.workspace:
      return toolName.startsWith('file_')
          ? toolName.substring('file_'.length)
          : toolName;
    case AgentToolActivityKind.workbench:
      return toolName.startsWith('workbench_')
          ? toolName.substring('workbench_'.length)
          : toolName;
    case AgentToolActivityKind.mcp:
      return toolName;
  }
}

String _resolveTarget(
  AgentToolActivityKind kind,
  Map<String, dynamic> cardData,
  Map<String, dynamic> args,
  String action,
) {
  switch (kind) {
    case AgentToolActivityKind.browser:
      return _firstNonBlank(<Object?>[
        args['url'],
        args['selector'],
        args['key'],
        _coordinates(args),
        action == 'type' ? null : args['text'],
        cardData['summary'],
        cardData['progress'],
      ]);
    case AgentToolActivityKind.research:
      return _firstNonBlank(<Object?>[
        args['query'],
        args['imagePath'],
        args['image_path'],
        args['source'],
        cardData['summary'],
        cardData['progress'],
      ]);
    case AgentToolActivityKind.terminal:
      return _firstNonBlank(<Object?>[
        args['command'],
        args['cmd'],
        cardData['summary'],
        cardData['progress'],
      ]);
    case AgentToolActivityKind.workspace:
      return _firstNonBlank(<Object?>[
        args['path'],
        args['filePath'],
        args['file_path'],
        args['query'],
        cardData['summary'],
        cardData['progress'],
      ]);
    case AgentToolActivityKind.workbench:
      return _firstNonBlank(<Object?>[
        args['projectId'],
        args['project_id'],
        args['name'],
        cardData['summary'],
        cardData['progress'],
      ]);
    case AgentToolActivityKind.mcp:
      return _firstNonBlank(<Object?>[
        args['path'],
        args['query'],
        args['url'],
        cardData['summary'],
        cardData['progress'],
      ]);
  }
}

String _actionKey(
  AgentToolActivityKind kind,
  Map<String, dynamic> cardData,
  Map<String, dynamic> args,
  String action,
  String target,
) {
  switch (kind) {
    case AgentToolActivityKind.browser:
      return _normalizeKey('$action|$target');
    case AgentToolActivityKind.research:
      return _normalizeKey('$action|$target');
    case AgentToolActivityKind.terminal:
      return _normalizeKey(
        '$action|${_firstNonBlank(<Object?>[cardData['terminalSessionId'], args['terminalSessionId'], args['command'], target])}',
      );
    case AgentToolActivityKind.workspace:
    case AgentToolActivityKind.workbench:
    case AgentToolActivityKind.mcp:
      return _normalizeKey('$action|$target');
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
    final target = current.target.trim();
    if (target.isNotEmpty) return target;
  }
  if (deduped.length == 1) {
    final t = deduped.single.title.trim();
    if (t.isNotEmpty) return t;
  }
  return _activityKindLabel(kind);
}

String _activityKindLabel(AgentToolActivityKind kind) {
  switch (kind) {
    case AgentToolActivityKind.browser:
      return 'Browser activity';
    case AgentToolActivityKind.research:
      return 'Research activity';
    case AgentToolActivityKind.terminal:
      return 'Terminal activity';
    case AgentToolActivityKind.workspace:
      return 'Workspace activity';
    case AgentToolActivityKind.workbench:
      return 'Workbench activity';
    case AgentToolActivityKind.mcp:
      return 'MCP activity';
  }
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
    if (_isFailureStatus(s)) hasError = true;
    if (s != 'success') allSuccess = false;
  }
  if (hasRunning) return 'running';
  final finalStatus = candidates.last.status;
  if (finalStatus == 'success' ||
      finalStatus == 'interrupted' ||
      _isFailureStatus(finalStatus)) {
    return _isFailureStatus(finalStatus) ? 'error' : finalStatus;
  }
  if (hasInterrupted) return 'interrupted';
  if (hasError) return 'error';
  if (allSuccess) return 'success';
  return finalStatus;
}

String _normalizeStatus(dynamic raw) {
  final status = raw?.toString().trim().toLowerCase() ?? '';
  if (status.isEmpty) {
    return 'running';
  }
  if (status == 'failed' || status == 'failure') {
    return 'error';
  }
  return status;
}

bool _isFailureStatus(String status) {
  return status == 'error' || status == 'failed' || status == 'failure';
}

Map<String, dynamic> _decodeJsonMap(String raw) {
  final text = raw.trim();
  if (text.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(text);
    if (decoded is Map) {
      return decoded.map((key, value) => MapEntry(key.toString(), value));
    }
  } catch (_) {}
  return const <String, dynamic>{};
}

String _coordinates(Map<String, dynamic> args) {
  final x = _firstNonBlank(<Object?>[args['x'], args['clientX']]);
  final y = _firstNonBlank(<Object?>[args['y'], args['clientY']]);
  if (x.isEmpty || y.isEmpty) {
    return '';
  }
  return '$x,$y';
}

String _firstNonBlank(Iterable<Object?> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
}

String _normalizeKey(String value) {
  return value.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');
}
