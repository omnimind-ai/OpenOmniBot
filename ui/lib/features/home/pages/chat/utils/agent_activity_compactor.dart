import 'dart:convert';

import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/models/chat_message_model.dart';

const String kAgentToolActivityCardType = 'agent_tool_summary';

enum AgentToolActivityKind { browser, terminal, workspace, workbench, mcp }

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
    required this.message,
  });

  final String cardId;
  final String title;
  final String action;
  final String target;
  final String status;
  final bool isRetry;
  final ChatMessageModel message;
}

List<AgentProcessItem> compactAgentProcessItems(
  List<ChatMessageModel> processMessages,
) {
  final items = <AgentProcessItem>[];
  _ActivityBuilder? pending;

  void flushPending() {
    final builder = pending;
    if (builder == null) {
      return;
    }
    items.add(builder.buildItem());
    pending = null;
  }

  for (final message in processMessages) {
    if (isStaleAgentToolPreviewPlaceholder(message)) {
      continue;
    }
    final candidate = _ToolActivityCandidate.tryParse(message);
    if (candidate == null) {
      flushPending();
      items.add(AgentProcessItem.message(message));
      continue;
    }

    final builder = pending;
    if (builder != null && builder.canAppend(candidate)) {
      builder.append(candidate);
      continue;
    }

    flushPending();
    pending = _ActivityBuilder(candidate);
  }

  flushPending();
  return items;
}

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
  final List<_ToolActivityCandidate> _candidates = <_ToolActivityCandidate>[];

  bool canAppend(_ToolActivityCandidate candidate) {
    return candidate.activityKey == activityKey;
  }

  void append(_ToolActivityCandidate candidate) {
    _candidates.add(candidate);
  }

  AgentProcessItem buildItem() {
    if (_candidates.length < 2) {
      return AgentProcessItem.message(_candidates.single.message);
    }

    final steps = <AgentToolActivityStep>[];
    String previousActionKey = '';
    String previousStatus = '';
    for (final candidate in _candidates) {
      final actionKey = candidate.actionKey;
      final isRetry =
          actionKey.isNotEmpty &&
          previousActionKey == actionKey &&
          (_isFailureStatus(previousStatus) || steps.isNotEmpty);
      steps.add(candidate.toStep(isRetry: isRetry));
      previousActionKey = actionKey;
      previousStatus = candidate.status;
    }

    final id = _firstNonBlank(<Object?>[
      _candidates.first.taskId,
      _candidates.first.cardId,
      activityKey,
    ]);
    return AgentProcessItem.activity(
      AgentToolActivity(
        id: '$id-${kind.name}-activity',
        kind: kind,
        title: _activityTitle(kind, _candidates),
        status: _resolveActivityStatus(_candidates),
        taskId: taskId,
        messages: _candidates
            .map((candidate) => candidate.message)
            .toList(growable: false),
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

  AgentToolActivityStep toStep({required bool isRetry}) {
    return AgentToolActivityStep(
      cardId: cardId,
      title: title,
      action: action,
      target: target,
      status: status,
      isRetry: isRetry,
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
  List<_ToolActivityCandidate> candidates,
) {
  final lastExplicit = candidates.reversed
      .map((candidate) => candidate.title.trim())
      .firstWhere((title) => title.isNotEmpty, orElse: () => '');
  if (lastExplicit.isNotEmpty && candidates.length == 1) {
    return lastExplicit;
  }
  return _activityKindLabel(kind);
}

String _activityKindLabel(AgentToolActivityKind kind) {
  switch (kind) {
    case AgentToolActivityKind.browser:
      return 'Browser activity';
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
  if (candidates.any((candidate) => candidate.status == 'running')) {
    return 'running';
  }
  final finalStatus = candidates.last.status;
  if (finalStatus == 'success' ||
      finalStatus == 'interrupted' ||
      _isFailureStatus(finalStatus)) {
    return finalStatus == 'failed' || finalStatus == 'failure'
        ? 'error'
        : finalStatus;
  }
  if (candidates.any((candidate) => candidate.status == 'interrupted')) {
    return 'interrupted';
  }
  if (candidates.any((candidate) => _isFailureStatus(candidate.status))) {
    return 'error';
  }
  if (candidates.every((candidate) => candidate.status == 'success')) {
    return 'success';
  }
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
