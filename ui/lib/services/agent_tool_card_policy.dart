import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';

const String kAgentToolSummaryCardType = 'agent_tool_summary';

enum AgentToolActivityKind {
  thinking,
  browser,
  research,
  vlm,
  terminal,
  workspace,
  workbench,
  mcp,
  generic,
}

class AgentToolCardIdentity {
  const AgentToolCardIdentity({
    required this.primaryId,
    required this.ids,
    this.taskId = '',
  });

  final String primaryId;
  final Set<String> ids;
  final String taskId;

  bool get isEmpty => primaryId.isEmpty && ids.isEmpty;

  bool matches(AgentToolCardIdentity other) {
    if (ids.isEmpty || other.ids.isEmpty) {
      return false;
    }
    return ids.any(other.ids.contains);
  }
}

class AgentToolRunLogRef {
  const AgentToolRunLogRef({required this.runLogId, required this.cardId});

  final String runLogId;
  final String cardId;

  bool get hasRunLog => runLogId.isNotEmpty;
  bool get hasStep => runLogId.isNotEmpty && cardId.isNotEmpty;
}

class AgentToolTerminalOutputPolicy {
  const AgentToolTerminalOutputPolicy._();

  static const int defaultMaxChars = 64 * 1024;
  static const int defaultMaxLines = 600;

  static String merge({
    required String existing,
    required String full,
    required String delta,
    int maxChars = defaultMaxChars,
    int maxLines = defaultMaxLines,
  }) {
    final next = full.isNotEmpty
        ? full
        : delta.isNotEmpty
        ? existing + delta
        : existing;
    return trim(next, maxChars: maxChars, maxLines: maxLines);
  }

  static String trim(
    String value, {
    int maxChars = defaultMaxChars,
    int maxLines = defaultMaxLines,
  }) {
    var text = value;
    if (maxChars > 0 && text.length > maxChars) {
      text = text.substring(text.length - maxChars);
      final newline = text.indexOf('\n');
      if (newline >= 0 && newline + 1 < text.length) {
        text = text.substring(newline + 1);
      }
    }
    if (maxLines > 0) {
      final lines = text.split('\n');
      if (lines.length > maxLines) {
        text = lines.sublist(lines.length - maxLines).join('\n');
      }
    }
    return text;
  }

  static String trimForDisplay(
    String value, {
    int maxChars = defaultMaxChars,
    int maxLines = defaultMaxLines,
    String truncationNotice = '',
  }) {
    if (value.isEmpty) return value;
    final trimmed = trim(value, maxChars: maxChars, maxLines: maxLines);
    if (trimmed.length == value.length) {
      return trimmed;
    }
    final notice = truncationNotice.trimRight();
    if (notice.isEmpty || trimmed.startsWith(notice)) {
      return trimmed;
    }
    final prefix = '$notice\n';
    final remaining = maxChars > prefix.length ? maxChars - prefix.length : 0;
    final body = remaining > 0 && trimmed.length > remaining
        ? trimmed.substring(trimmed.length - remaining)
        : trimmed;
    return '$prefix$body';
  }
}

class AgentToolCardPolicy {
  const AgentToolCardPolicy._();

  static bool isToolCard(Map<String, dynamic>? cardData) {
    return (cardData?['type'] ?? '').toString() == kAgentToolSummaryCardType;
  }

  static String firstNonBlank(Iterable<Object?> values) {
    for (final value in values) {
      final normalized = value?.toString().trim() ?? '';
      if (normalized.isNotEmpty) {
        return normalized;
      }
    }
    return '';
  }

  static Map<String, dynamic> asStringMap(dynamic value) {
    if (value is! Map) {
      return const <String, dynamic>{};
    }
    return value.map((key, item) => MapEntry(key.toString(), item));
  }

  static List<Map<String, dynamic>> asMapList(dynamic raw) {
    if (raw is! List) {
      return const <Map<String, dynamic>>[];
    }
    return raw
        .whereType<Map>()
        .map(
          (item) => item.map((key, value) => MapEntry(key.toString(), value)),
        )
        .toList(growable: false);
  }

  static Map<String, dynamic> decodeJsonMap(String raw) {
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

  static String normalizeStatus(Object? raw, {String fallback = 'running'}) {
    final status = raw?.toString().trim().toLowerCase() ?? '';
    if (status.isEmpty) {
      return fallback;
    }
    return switch (status) {
      'failed' || 'failure' => 'error',
      'cancelled' || 'canceled' || 'aborted' => 'interrupted',
      _ => status,
    };
  }

  static bool isFailureStatus(Object? raw) {
    return normalizeStatus(raw, fallback: '') == 'error';
  }

  static AgentToolActivityKind? activityKindFor(Map<String, dynamic> cardData) {
    final toolType = (cardData['toolType'] ?? '').toString().trim();
    final toolName = (cardData['toolName'] ?? '').toString().trim();
    final toolTypeLower = toolType.toLowerCase();
    final toolNameLower = toolName.toLowerCase();

    if (toolTypeLower == 'thinking' || toolNameLower == 'deep_thinking') {
      return AgentToolActivityKind.thinking;
    }
    if (toolTypeLower == 'browser' || toolNameLower == 'browser_use') {
      return AgentToolActivityKind.browser;
    }
    final compileKind = firstNonBlank(<Object?>[
      cardData['compile_kind'],
      cardData['compileKind'],
    ]).toLowerCase();
    final selectionSource = firstNonBlank(<Object?>[
      cardData['selection_source'],
      cardData['selectionSource'],
    ]).toLowerCase();
    final toolTypeSnake = (cardData['tool_type'] ?? '')
        .toString()
        .trim()
        .toLowerCase();

    if (toolTypeLower == 'vlm' ||
        toolTypeLower == 'mobile' ||
        toolTypeSnake == 'vlm' ||
        toolNameLower == 'vlm_task' ||
        compileKind == 'vlm_step' ||
        compileKind == 'vlm' ||
        selectionSource == 'vlm') {
      return AgentToolActivityKind.vlm;
    }
    if (toolTypeLower == 'research' ||
        toolNameLower == 'web_search' ||
        toolNameLower == 'image_picker') {
      return AgentToolActivityKind.research;
    }
    if (toolTypeLower == 'terminal' || toolNameLower.startsWith('terminal_')) {
      return AgentToolActivityKind.terminal;
    }
    if (toolTypeLower == 'workspace' || toolNameLower.startsWith('file_')) {
      return AgentToolActivityKind.workspace;
    }
    if (toolTypeLower == 'workbench' ||
        toolNameLower.startsWith('workbench_')) {
      return AgentToolActivityKind.workbench;
    }
    if (toolTypeLower == 'mcp') {
      return AgentToolActivityKind.mcp;
    }
    return AgentToolActivityKind.generic;
  }

  static String toolTypeForKind(AgentToolActivityKind kind) {
    return switch (kind) {
      AgentToolActivityKind.thinking => 'thinking',
      AgentToolActivityKind.browser => 'browser',
      AgentToolActivityKind.research => 'research',
      AgentToolActivityKind.vlm => 'vlm',
      AgentToolActivityKind.terminal => 'terminal',
      AgentToolActivityKind.workspace => 'workspace',
      AgentToolActivityKind.workbench => 'workbench',
      AgentToolActivityKind.mcp => 'mcp',
      AgentToolActivityKind.generic => 'tool',
    };
  }

  static Color activityColor(AgentToolActivityKind kind) {
    return switch (kind) {
      AgentToolActivityKind.thinking => const Color(0xFF8B5CF6),
      AgentToolActivityKind.browser => const Color(0xFF2563EB),
      AgentToolActivityKind.research => const Color(0xFF7C3AED),
      AgentToolActivityKind.vlm => const Color(0xFFDB2777),
      AgentToolActivityKind.terminal => const Color(0xFF0F9F6E),
      AgentToolActivityKind.workspace => const Color(0xFFD97706),
      AgentToolActivityKind.workbench => const Color(0xFF0891B2),
      AgentToolActivityKind.mcp => const Color(0xFF4F46E5),
      AgentToolActivityKind.generic => const Color(0xFF64748B),
    };
  }

  static Color activityColorForCard(Map<String, dynamic> cardData) {
    return activityColor(
      activityKindFor(cardData) ?? AgentToolActivityKind.generic,
    );
  }

  static String activityKindLabel(AgentToolActivityKind kind) {
    return switch (kind) {
      AgentToolActivityKind.thinking => 'Thinking',
      AgentToolActivityKind.browser => 'Browser activity',
      AgentToolActivityKind.research => 'Research activity',
      AgentToolActivityKind.vlm => 'Visual task',
      AgentToolActivityKind.terminal => 'Terminal activity',
      AgentToolActivityKind.workspace => 'Workspace activity',
      AgentToolActivityKind.workbench => 'Workbench activity',
      AgentToolActivityKind.mcp => 'MCP activity',
      AgentToolActivityKind.generic => 'Tool activity',
    };
  }

  static bool isPlaceholderText(Object? raw) {
    final text = raw?.toString().trim().toLowerCase() ?? '';
    return text.isEmpty ||
        text == 'calling tool' ||
        text == 'calling tool...' ||
        text == 'preparing tool call...' ||
        text == '正在准备工具调用...' ||
        text == '工具调用中' ||
        text == '执行中';
  }

  static bool isGenericTerminalProgressText(Object? raw) {
    final normalized = raw?.toString().trim() ?? '';
    if (normalized.isEmpty) {
      return true;
    }
    return isPlaceholderText(normalized) ||
        normalized == '正在调用内嵌 Alpine 终端执行命令' ||
        normalized == '正在执行内嵌 Alpine 终端命令' ||
        normalized == '终端输出更新中' ||
        normalized == 'Running a command in the embedded Alpine terminal' ||
        normalized == 'Executing a command in the embedded Alpine terminal' ||
        normalized == 'Updating terminal output';
  }

  static String semanticTitleForStep({
    required AgentToolActivityKind kind,
    required String title,
    required String action,
    required String target,
    required Map<String, dynamic> cardData,
    required Map<String, dynamic> args,
    bool isCurrent = false,
  }) {
    final meaningfulTarget = firstNonBlank(<Object?>[
      target,
      _kindSpecificTarget(kind, args),
    ]);
    final normalizedTitle = title.trim();
    final explicitTitle = firstNonBlank(<Object?>[
      cardData['toolTitle'],
      cardData['tool_title'],
      args['tool_title'],
    ]);
    final meaningfulTitle =
        isPlaceholderText(normalizedTitle) ||
            normalizedTitle == activityKindLabel(kind)
        ? ''
        : normalizedTitle;
    final meaningfulAction = isPlaceholderText(action) ? '' : action.trim();

    // Folded activity rows should answer "what is happening to what?".
    // Humans scan the object first (command/path/query/url), then operation.
    if (explicitTitle.isNotEmpty && !isPlaceholderText(explicitTitle)) {
      if (kind == AgentToolActivityKind.vlm && meaningfulTarget.isNotEmpty) {
        return meaningfulTarget;
      }
      return explicitTitle;
    }
    if (isCurrent && meaningfulTarget.isNotEmpty) {
      return meaningfulTarget;
    }
    if (meaningfulTarget.isNotEmpty) {
      return meaningfulTarget;
    }
    if (meaningfulTitle.isNotEmpty && meaningfulTitle != meaningfulAction) {
      return meaningfulTitle;
    }
    if (meaningfulAction.isNotEmpty) {
      return meaningfulAction;
    }
    return firstNonBlank(<Object?>[
      cardData['displayName'],
      cardData['toolName'],
      activityKindLabel(kind),
    ]);
  }

  static String actionFor(
    AgentToolActivityKind kind,
    Map<String, dynamic> cardData,
    Map<String, dynamic> args,
  ) {
    final explicit = firstNonBlank(<Object?>[
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
      case AgentToolActivityKind.thinking:
        return firstNonBlank(<Object?>[
          cardData['summary'],
          cardData['progress'],
          cardData['thinkingContent'],
          toolName,
        ]);
      case AgentToolActivityKind.browser:
        return toolName == 'browser_use' ? 'browser' : toolName;
      case AgentToolActivityKind.research:
        return toolName == 'web_search' ? 'search' : toolName;
      case AgentToolActivityKind.vlm:
        if (toolName.isNotEmpty && toolName != 'vlm_task') {
          return toolName;
        }
        return firstNonBlank(<Object?>[
          args['action_type'],
          args['actionType'],
          args['action'],
          args['operation'],
          toolName,
        ]);
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
      case AgentToolActivityKind.generic:
        return firstNonBlank(<Object?>[
          args['action'],
          args['operation'],
          cardData['action'],
          toolName,
          cardData['displayName'],
        ]);
    }
  }

  static String targetFor(
    AgentToolActivityKind kind,
    Map<String, dynamic> cardData,
    Map<String, dynamic> args,
    String action,
  ) {
    switch (kind) {
      case AgentToolActivityKind.thinking:
        return firstNonBlank(<Object?>[
          cardData['thinkingContent'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.browser:
        return firstNonBlank(<Object?>[
          args['url'],
          args['selector'],
          args['key'],
          coordinates(args),
          action == 'type' ? null : args['text'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.research:
        return firstNonBlank(<Object?>[
          args['query'],
          args['imagePath'],
          args['image_path'],
          args['source'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.vlm:
        return firstNonBlank(<Object?>[
          args['target_description'],
          args['targetDescription'],
          args['content'],
          args['text'],
          args['prompt'],
          args['value'],
          args['package_name'],
          args['packageName'],
          args['key'],
          args['goal'],
          args['duration_ms'],
          args['durationMs'],
          args['duration'],
          coordinates(args),
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.terminal:
        return firstNonBlank(<Object?>[
          args['command'],
          args['cmd'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.workspace:
        return firstNonBlank(<Object?>[
          args['path'],
          args['filePath'],
          args['file_path'],
          args['query'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.workbench:
        return firstNonBlank(<Object?>[
          args['projectId'],
          args['project_id'],
          args['name'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.mcp:
        return firstNonBlank(<Object?>[
          args['path'],
          args['query'],
          args['url'],
          cardData['summary'],
          cardData['progress'],
        ]);
      case AgentToolActivityKind.generic:
        return firstNonBlank(<Object?>[
          args['path'],
          args['filePath'],
          args['file_path'],
          args['query'],
          args['url'],
          args['command'],
          args['cmd'],
          cardData['summary'],
          cardData['progress'],
        ]);
    }
  }

  static String _kindSpecificTarget(
    AgentToolActivityKind kind,
    Map<String, dynamic> args,
  ) {
    switch (kind) {
      case AgentToolActivityKind.thinking:
        return firstNonBlank(<Object?>[args['text'], args['content']]);
      case AgentToolActivityKind.browser:
        return firstNonBlank(<Object?>[
          args['url'],
          args['selector'],
          args['key'],
          coordinates(args),
          args['text'],
        ]);
      case AgentToolActivityKind.research:
        return firstNonBlank(<Object?>[
          args['query'],
          args['imagePath'],
          args['image_path'],
          args['source'],
        ]);
      case AgentToolActivityKind.vlm:
        return firstNonBlank(<Object?>[
          args['target_description'],
          args['targetDescription'],
          args['content'],
          args['text'],
          args['prompt'],
          args['value'],
          args['package_name'],
          args['packageName'],
          args['key'],
          args['goal'],
          args['duration_ms'],
          args['durationMs'],
          args['duration'],
          coordinates(args),
        ]);
      case AgentToolActivityKind.terminal:
        return firstNonBlank(<Object?>[args['command'], args['cmd']]);
      case AgentToolActivityKind.workspace:
        return firstNonBlank(<Object?>[
          args['path'],
          args['filePath'],
          args['file_path'],
          args['query'],
        ]);
      case AgentToolActivityKind.workbench:
        return firstNonBlank(<Object?>[
          args['projectId'],
          args['project_id'],
          args['name'],
        ]);
      case AgentToolActivityKind.mcp:
        return firstNonBlank(<Object?>[
          args['path'],
          args['query'],
          args['url'],
        ]);
      case AgentToolActivityKind.generic:
        return firstNonBlank(<Object?>[
          args['path'],
          args['filePath'],
          args['file_path'],
          args['query'],
          args['url'],
          args['command'],
          args['cmd'],
        ]);
    }
  }

  static String coordinates(Map<String, dynamic> args) {
    final x = firstNonBlank(<Object?>[args['x'], args['clientX']]);
    final y = firstNonBlank(<Object?>[args['y'], args['clientY']]);
    if (x.isEmpty || y.isEmpty) {
      return '';
    }
    return '$x,$y';
  }

  static String actionKeyFor(
    AgentToolActivityKind kind,
    Map<String, dynamic> cardData,
    Map<String, dynamic> args,
    String action,
    String target,
  ) {
    switch (kind) {
      case AgentToolActivityKind.thinking:
        return normalizeKey('thinking|$target');
      case AgentToolActivityKind.browser:
      case AgentToolActivityKind.research:
      case AgentToolActivityKind.vlm:
      case AgentToolActivityKind.workspace:
      case AgentToolActivityKind.workbench:
      case AgentToolActivityKind.mcp:
      case AgentToolActivityKind.generic:
        return normalizeKey('$action|$target');
      case AgentToolActivityKind.terminal:
        return normalizeKey(
          '$action|${firstNonBlank(<Object?>[cardData['terminalSessionId'], cardData['terminal_session_id'], args['terminalSessionId'], args['terminal_session_id'], args['command'], target])}',
        );
    }
  }

  static String activityKeyFor(
    AgentToolActivityKind kind,
    String taskId,
    Map<String, dynamic> cardData,
    Map<String, dynamic> args,
  ) {
    switch (kind) {
      case AgentToolActivityKind.thinking:
        return '$taskId|thinking';
      case AgentToolActivityKind.browser:
        return '$taskId|browser';
      case AgentToolActivityKind.research:
        return '$taskId|research';
      case AgentToolActivityKind.vlm:
        return '$taskId|vlm';
      case AgentToolActivityKind.terminal:
        final sessionId = firstNonBlank(<Object?>[
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
        final serverName = firstNonBlank(<Object?>[
          cardData['serverName'],
          cardData['server_name'],
        ]);
        return serverName.isEmpty ? '$taskId|mcp' : '$taskId|mcp|$serverName';
      case AgentToolActivityKind.generic:
        final toolType = firstNonBlank(<Object?>[
          cardData['toolType'],
          cardData['tool_type'],
          'tool',
        ]);
        final toolName = firstNonBlank(<Object?>[
          cardData['toolName'],
          cardData['tool_name'],
          cardData['displayName'],
          cardData['display_name'],
        ]);
        return normalizeKey('$taskId|$toolType|$toolName');
    }
  }

  static String normalizeKey(String value) {
    return value.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');
  }

  static AgentToolCardIdentity identityFromCard(
    Map<String, dynamic>? cardData, {
    ChatMessageModel? message,
    String fallback = '',
  }) {
    final card = cardData ?? message?.cardData ?? const <String, dynamic>{};
    final ids = _normalizeIdentitySet(<Object?>[
      card['toolCallId'],
      card['tool_call_id'],
      card['callId'],
      card['call_id'],
      card['cardId'],
      card['card_id'],
      card['toolTaskId'],
      card['tool_task_id'],
      message?.streamMeta?['entryId'],
      message?.contentId,
      message?.id,
      fallback,
    ]);
    return AgentToolCardIdentity(
      primaryId: firstNonBlank(<Object?>[
        card['toolCallId'],
        card['tool_call_id'],
        card['callId'],
        card['call_id'],
        card['cardId'],
        card['card_id'],
        card['toolTaskId'],
        card['tool_task_id'],
        message?.streamMeta?['entryId'],
        message?.contentId,
        message?.id,
        fallback,
      ]),
      ids: ids,
      taskId: taskIdForMessage(message, cardData: card) ?? '',
    );
  }

  static String operationIdFromCard(
    Map<String, dynamic>? cardData, {
    ChatMessageModel? message,
  }) {
    final card = cardData ?? message?.cardData ?? const <String, dynamic>{};
    return firstNonBlank(<Object?>[
      card['toolCallId'],
      card['tool_call_id'],
      card['callId'],
      card['call_id'],
      card['toolTaskId'],
      card['tool_task_id'],
      card['cardId'],
      card['card_id'],
    ]);
  }

  static AgentToolCardIdentity identityFromEvent(
    AgentStreamEvent event, {
    Map<dynamic, dynamic>? raw,
  }) {
    final source = raw ?? event.raw;
    final primaryId = firstNonBlank(<Object?>[
      source['toolCallId'],
      source['tool_call_id'],
      event.raw['toolCallId'],
      event.raw['tool_call_id'],
      source['callId'],
      source['call_id'],
      event.raw['callId'],
      event.raw['call_id'],
      source['cardId'],
      source['card_id'],
      event.raw['cardId'],
      event.raw['card_id'],
      source['toolTaskId'],
      source['tool_task_id'],
      event.raw['toolTaskId'],
      event.raw['tool_task_id'],
      event.entryId,
    ]);
    final ids = _normalizeIdentitySet(<Object?>[
      source['toolCallId'],
      source['tool_call_id'],
      source['callId'],
      source['call_id'],
      source['cardId'],
      source['card_id'],
      source['toolTaskId'],
      source['tool_task_id'],
      event.raw['toolCallId'],
      event.raw['tool_call_id'],
      event.raw['callId'],
      event.raw['call_id'],
      event.raw['cardId'],
      event.raw['card_id'],
      event.raw['toolTaskId'],
      event.raw['tool_task_id'],
      event.entryId,
    ]);
    return AgentToolCardIdentity(
      primaryId: primaryId,
      ids: ids,
      taskId: event.taskId,
    );
  }

  static String operationIdFromEvent(
    AgentStreamEvent event, {
    Map<dynamic, dynamic>? raw,
  }) {
    final source = raw ?? event.raw;
    return firstNonBlank(<Object?>[
      source['toolCallId'],
      source['tool_call_id'],
      event.raw['toolCallId'],
      event.raw['tool_call_id'],
      source['callId'],
      source['call_id'],
      event.raw['callId'],
      event.raw['call_id'],
      source['toolTaskId'],
      source['tool_task_id'],
      event.raw['toolTaskId'],
      event.raw['tool_task_id'],
      source['cardId'],
      source['card_id'],
      event.raw['cardId'],
      event.raw['card_id'],
    ]);
  }

  static String cardIdForEvent(
    AgentStreamEvent event, {
    Map<dynamic, dynamic>? raw,
  }) {
    return identityFromEvent(event, raw: raw).primaryId;
  }

  static String? taskIdForMessage(
    ChatMessageModel? message, {
    Map<String, dynamic>? cardData,
  }) {
    final card = cardData ?? message?.cardData;
    final value = firstNonBlank(<Object?>[
      card?['taskId'],
      card?['taskID'],
      message?.streamMeta?['parentTaskId'],
      message?.streamMeta?['runLogId'],
      message?.streamMeta?['run_id'],
      card?['runLogId'],
      card?['run_id'],
      card?['toolTaskId'],
      card?['tool_task_id'],
    ]);
    return value.isEmpty ? null : value;
  }

  static AgentToolRunLogRef runLogRef(
    Map<String, dynamic>? cardData, {
    ChatMessageModel? message,
  }) {
    final card = cardData ?? message?.cardData ?? const <String, dynamic>{};
    final streamMeta = <String, dynamic>{
      ...asStringMap(card['streamMeta']),
      ...asStringMap(message?.streamMeta),
    };
    final identity = identityFromCard(card, message: message);
    final runLogId = firstNonBlank(<Object?>[
      streamMeta['runLogId'],
      streamMeta['run_log_id'],
      streamMeta['runId'],
      streamMeta['run_id'],
      card['runLogId'],
      card['run_log_id'],
      card['runId'],
      card['run_id'],
      card['toolTaskId'],
      card['taskId'],
      card['taskID'],
      _runLogIdFromJsonString(card['resultPreviewJson']),
      _runLogIdFromJsonString(card['rawResultJson']),
    ]);
    return AgentToolRunLogRef(runLogId: runLogId, cardId: identity.primaryId);
  }

  static bool cardMatchesId(
    Map<String, dynamic>? cardData,
    String targetId, {
    ChatMessageModel? message,
  }) {
    final target = targetId.trim().toLowerCase();
    if (target.isEmpty) {
      return false;
    }
    final card = cardData ?? message?.cardData ?? const <String, dynamic>{};
    final toolCall = asStringMap(card['tool_call']);
    final toolCallAlt = asStringMap(card['toolCall']);
    final action = asStringMap(card['action']);
    final call = asStringMap(card['call']);
    final ids = _normalizeIdentitySet(<Object?>[
      ...identityFromCard(card, message: message).ids,
      card['id'],
      toolCall['id'],
      toolCall['tool_call_id'],
      toolCall['toolCallId'],
      toolCall['call_id'],
      toolCall['callId'],
      toolCallAlt['id'],
      toolCallAlt['tool_call_id'],
      toolCallAlt['toolCallId'],
      toolCallAlt['call_id'],
      toolCallAlt['callId'],
      action['id'],
      action['tool_call_id'],
      action['toolCallId'],
      call['id'],
      call['tool_call_id'],
      call['toolCallId'],
    ]);
    return ids.any((id) => id.toLowerCase() == target);
  }

  static Color statusColor(String status) {
    return switch (normalizeStatus(status, fallback: 'running')) {
      'success' => const Color(0xFF2F8F4E),
      'error' => const Color(0xFFFF6464),
      'timeout' => const Color(0xFFB45309),
      'interrupted' => const Color(0xFFFFAA2C),
      _ => const Color(0xFF2C7FEB),
    };
  }

  static IconData statusIcon(String status, String toolType) {
    final normalizedStatus = normalizeStatus(status, fallback: 'running');
    if (normalizedStatus == 'timeout') {
      return Icons.hourglass_top_rounded;
    }
    if (normalizedStatus == 'interrupted') {
      return Icons.stop_circle_outlined;
    }
    if (normalizedStatus == 'error') {
      return Icons.error_outline_rounded;
    }
    if (toolType == 'terminal') {
      return Icons.terminal_rounded;
    }
    if (toolType == 'browser') {
      return Icons.language_rounded;
    }
    if (toolType == 'vlm' || toolType == 'mobile') {
      return Icons.visibility_rounded;
    }
    if (toolType == 'calendar') {
      return Icons.calendar_month_rounded;
    }
    if (toolType == 'alarm' || toolType == 'schedule') {
      return Icons.alarm_rounded;
    }
    if (toolType == 'memory') {
      return Icons.psychology_alt_rounded;
    }
    if (toolType == 'workspace') {
      return Icons.folder_outlined;
    }
    if (toolType == 'workbench') {
      return Icons.dashboard_customize_outlined;
    }
    if (toolType == 'subagent') {
      return Icons.hub_outlined;
    }
    if (toolType == 'mcp') {
      return Icons.extension_outlined;
    }
    return Icons.check_circle_outline_rounded;
  }

  static Set<String> _normalizeIdentitySet(Iterable<Object?> values) {
    final result = <String>{};
    for (final value in values) {
      final normalized = value?.toString().trim() ?? '';
      if (normalized.isNotEmpty) {
        result.add(normalized);
      }
    }
    return result;
  }

  static String _runLogIdFromJsonString(dynamic raw) {
    final text = raw?.toString().trim() ?? '';
    if (text.isEmpty) return '';
    try {
      final decoded = jsonDecode(text);
      if (decoded is! Map) return '';
      return firstNonBlank(<Object?>[
        decoded['runLogId'],
        decoded['run_log_id'],
        decoded['runId'],
        decoded['run_id'],
        decoded['taskId'],
        decoded['task_id'],
      ]);
    } catch (_) {
      return '';
    }
  }
}
