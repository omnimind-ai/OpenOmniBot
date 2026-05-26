import 'dart:ui';

import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_card_policy.dart' as tool_policy;

const String kAgentToolSummaryCardType = tool_policy.kAgentToolSummaryCardType;
const String kAgentToolTitleField = 'toolTitle';
const int _kToolCardTitleMaxChars = 80;
const int _kToolCardPreviewMaxChars = 160;
// Keep raw result payloads bounded. Claude Code caps tool output at 30K chars;
// we use 8K since these are stored in Flutter memory per-message.
const int _kToolCardRawResultMaxChars = 8192;

class AgentToolActivitySnapshot {
  const AgentToolActivitySnapshot({
    required this.messages,
    required this.isActiveRun,
    this.taskId,
  });

  final List<ChatMessageModel> messages;
  final bool isActiveRun;
  final String? taskId;
}

bool shouldShowAgentToolActivitySnapshot(
  AgentToolActivitySnapshot snapshot, {
  Set<String> expandedTaskIds = const <String>{},
}) {
  if (snapshot.messages.isEmpty) {
    return false;
  }
  if (snapshot.isActiveRun) {
    return true;
  }
  final taskId = snapshot.taskId?.trim() ?? '';
  if (taskId.isEmpty) {
    return false;
  }
  final normalizedExpandedTaskIds = expandedTaskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  return normalizedExpandedTaskIds.contains(taskId);
}

List<Map<String, dynamic>> extractAgentToolCards(
  List<ChatMessageModel> messages,
) {
  final cards = <Map<String, dynamic>>[];
  final emittedKeys = <String>{};
  for (final message in messages) {
    if (isStaleAgentToolPreviewPlaceholder(message)) {
      continue;
    }
    final cardData = message.cardData;
    if (cardData == null ||
        (cardData['type'] ?? '').toString() != kAgentToolSummaryCardType) {
      continue;
    }
    final key = tool_policy.AgentToolCardPolicy.operationIdFromCard(
      cardData,
      message: message,
    );
    if (key.isNotEmpty && !emittedKeys.add(key)) {
      continue;
    }
    cards.add(cardData);
  }
  return cards;
}

List<Map<String, dynamic>> extractRunningAgentToolCards(
  List<ChatMessageModel> messages,
) {
  return extractAgentToolCards(messages)
      .where(
        (cardData) =>
            tool_policy.AgentToolCardPolicy.normalizeStatus(
              cardData['status'],
            ) ==
            'running',
      )
      .toList(growable: false);
}

List<ChatMessageModel> filterAgentToolMessagesByTaskIds(
  List<ChatMessageModel> messages,
  Set<String> taskIds,
) {
  final normalizedTaskIds = taskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  if (normalizedTaskIds.isEmpty) {
    return const <ChatMessageModel>[];
  }
  return messages
      .where((message) {
        if (isStaleAgentToolPreviewPlaceholder(message)) {
          return false;
        }
        if ((message.cardData?['type'] ?? '').toString() !=
            kAgentToolSummaryCardType) {
          return false;
        }
        final taskId = resolveAgentToolTaskId(message);
        return taskId != null && normalizedTaskIds.contains(taskId);
      })
      .toList(growable: false);
}

AgentToolActivitySnapshot resolveAgentToolActivitySnapshot(
  List<ChatMessageModel> messages, {
  Set<String> activeTaskIds = const <String>{},
  String? preferredCompletedTaskId,
}) {
  final normalizedActiveTaskIds = activeTaskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  final normalizedPreferredCompletedTaskId =
      preferredCompletedTaskId?.trim() ?? '';
  final activeMessages = filterAgentToolMessagesByTaskIds(
    messages,
    normalizedActiveTaskIds,
  );
  if (activeMessages.isNotEmpty) {
    return AgentToolActivitySnapshot(
      messages: activeMessages,
      isActiveRun: true,
      taskId:
          _resolveSnapshotTaskId(activeMessages) ??
          (normalizedActiveTaskIds.length == 1
              ? normalizedActiveTaskIds.first
              : null),
    );
  }
  if (normalizedActiveTaskIds.isNotEmpty) {
    return AgentToolActivitySnapshot(
      messages: <ChatMessageModel>[],
      isActiveRun: true,
      taskId: normalizedActiveTaskIds.length == 1
          ? normalizedActiveTaskIds.first
          : null,
    );
  }
  if (normalizedPreferredCompletedTaskId.isNotEmpty) {
    return AgentToolActivitySnapshot(
      messages: resolveAgentToolMessagesForTask(
        messages,
        normalizedPreferredCompletedTaskId,
      ),
      isActiveRun: false,
      taskId: normalizedPreferredCompletedTaskId,
    );
  }
  final latestCompletedRun = _resolveLatestCompletedAgentToolRun(messages);
  return AgentToolActivitySnapshot(
    messages: latestCompletedRun?.messages ?? const <ChatMessageModel>[],
    isActiveRun: false,
    taskId: latestCompletedRun?.taskId,
  );
}

List<ChatMessageModel> resolveLatestCompletedAgentToolMessages(
  List<ChatMessageModel> messages,
) {
  return _resolveLatestCompletedAgentToolRun(messages)?.messages ??
      const <ChatMessageModel>[];
}

List<ChatMessageModel> resolveAgentToolMessagesForTask(
  List<ChatMessageModel> messages,
  String taskId,
) {
  final normalizedTaskId = taskId.trim();
  if (normalizedTaskId.isEmpty || messages.isEmpty) {
    return const <ChatMessageModel>[];
  }
  final timelineEntries = buildAgentRunTimelineEntries(messages);
  for (final entry in timelineEntries) {
    final group = entry.group;
    if (group == null ||
        group.taskId != normalizedTaskId ||
        group.toolCount == 0) {
      continue;
    }
    return group.processMessagesNewestFirst
        .where(_isVisibleAgentToolSummaryMessage)
        .toList(growable: false);
  }
  return const <ChatMessageModel>[];
}

String? resolveAgentToolTaskId(ChatMessageModel message) {
  return tool_policy.AgentToolCardPolicy.taskIdForMessage(message);
}

Map<String, dynamic>? resolveActiveAgentToolCard(
  List<Map<String, dynamic>> cards,
) {
  for (final card in cards) {
    if (tool_policy.AgentToolCardPolicy.normalizeStatus(card['status']) ==
        'running') {
      return card;
    }
  }
  if (cards.isEmpty) {
    return null;
  }
  return cards.first;
}

Map<String, dynamic>? buildAgentToolActivityCardFromActiveRun(
  Map<String, dynamic> run,
) {
  final activeTool = tool_policy.AgentToolCardPolicy.asStringMap(
    run['activeTool'],
  );
  if (activeTool.isEmpty) {
    return null;
  }
  final extras = tool_policy.AgentToolCardPolicy.asStringMap(
    activeTool['extras'],
  );
  final raw = <String, dynamic>{...extras};
  for (final key in const [
    'toolName',
    'toolCallId',
    'cardId',
    'summary',
    'manualStopRequested',
    'completed',
  ]) {
    final value = activeTool[key];
    if (value != null) {
      raw[key] = value;
    }
  }
  final manualStopRequested = activeTool['manualStopRequested'] == true;
  final completed = activeTool['completed'] == true;
  return buildAgentToolActivityCard(
    raw,
    taskId: (run['taskId'] ?? '').toString(),
    defaultStatus: manualStopRequested
        ? 'interrupted'
        : completed
        ? 'success'
        : 'running',
  );
}

Map<String, dynamic> buildAgentToolActivityCard(
  Map<dynamic, dynamic> raw, {
  String? taskId,
  String defaultStatus = 'running',
}) {
  final card = raw.map((key, value) => MapEntry(key.toString(), value));
  card['type'] = kAgentToolSummaryCardType;

  final normalizedTaskId = taskId?.trim() ?? '';
  if (normalizedTaskId.isNotEmpty) {
    card['taskId'] = normalizedTaskId;
  } else {
    final existingTaskId = (card['taskId'] ?? '').toString().trim();
    if (existingTaskId.isNotEmpty) {
      card['taskId'] = existingTaskId;
    }
  }

  final toolName = (card['toolName'] ?? card['name'] ?? '').toString().trim();
  if (toolName.isNotEmpty) {
    card['toolName'] = toolName;
  }

  final status = (card['status'] ?? '').toString().trim();
  if (status.isEmpty) {
    card['status'] = defaultStatus;
  }

  final cardId = tool_policy.AgentToolCardPolicy.identityFromCard(
    card,
  ).primaryId;
  if (cardId.isNotEmpty) {
    card['cardId'] = cardId;
    final toolCallId = tool_policy.AgentToolCardPolicy.firstNonBlank([
      card['toolCallId'],
      card['tool_call_id'],
      card['callId'],
      card['call_id'],
    ]);
    if (toolCallId.isNotEmpty) {
      card['toolCallId'] = toolCallId;
    } else if (cardId != (card['toolTaskId'] ?? '').toString().trim()) {
      card['toolCallId'] = cardId;
    }
  }

  final progress = (card['progress'] ?? '').toString().trim();
  final summary = (card['summary'] ?? '').toString().trim();
  if (progress.isEmpty && summary.isNotEmpty) {
    card['progress'] = summary;
  } else if (summary.isEmpty && progress.isNotEmpty) {
    card['summary'] = progress;
  }

  final displayName = (card['displayName'] ?? '').toString().trim();
  if (displayName.isEmpty && toolName.isNotEmpty) {
    card['displayName'] = toolName;
  }

  // Truncate large result payloads. Full content is in RunLog; the card only
  // needs enough for preview and retry detection.
  for (final key in const [
    'rawResultJson',
    'resultPreviewJson',
    'terminalOutput',
  ]) {
    final raw = (card[key] ?? '').toString();
    if (raw.length > _kToolCardRawResultMaxChars) {
      card[key] =
          '${raw.substring(0, _kToolCardRawResultMaxChars)}\n…(truncated)';
    }
  }

  return card;
}

String _firstNonBlank(Iterable<Object?> values) {
  return tool_policy.AgentToolCardPolicy.firstNonBlank(values);
}

bool _isAgentToolSummaryMessage(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString() ==
      kAgentToolSummaryCardType;
}

bool _isVisibleAgentToolSummaryMessage(ChatMessageModel message) {
  return _isAgentToolSummaryMessage(message) &&
      !isStaleAgentToolPreviewPlaceholder(message);
}

bool isStaleAgentToolPreviewPlaceholder(ChatMessageModel message) {
  final cardData = message.cardData;
  if (cardData == null ||
      (cardData['type'] ?? '').toString() != kAgentToolSummaryCardType) {
    return false;
  }
  final streamMeta = <String, dynamic>{
    ...tool_policy.AgentToolCardPolicy.asStringMap(cardData['streamMeta']),
    ...tool_policy.AgentToolCardPolicy.asStringMap(message.streamMeta),
  };
  if ((streamMeta['kind'] ?? '').toString() != 'tool_started') {
    return false;
  }
  final status = tool_policy.AgentToolCardPolicy.normalizeStatus(
    cardData['status'],
  );
  if (status == 'running') {
    return false;
  }
  final args = _decodeJsonMap(
    _firstNonBlank(<Object?>[cardData['argsJson'], cardData['args']]),
  );
  if (args.isNotEmpty) {
    return false;
  }
  final preview = _firstNonBlank(<Object?>[
    cardData['resultPreviewJson'],
    cardData['rawResultJson'],
  ]);
  if (preview.isNotEmpty) {
    return false;
  }
  final summary = _firstNonBlank(<Object?>[
    cardData['summary'],
    cardData['progress'],
  ]);
  return tool_policy.AgentToolCardPolicy.isPlaceholderText(summary);
}

Map<String, dynamic> _decodeJsonMap(String raw) {
  return tool_policy.AgentToolCardPolicy.decodeJsonMap(raw);
}

String? _resolveSnapshotTaskId(List<ChatMessageModel> messages) {
  for (final message in messages) {
    final taskId = resolveAgentToolTaskId(message);
    if (taskId != null) {
      return taskId;
    }
  }
  return null;
}

_CompletedAgentToolRun? _resolveLatestCompletedAgentToolRun(
  List<ChatMessageModel> messages,
) {
  if (messages.isEmpty) {
    return null;
  }
  final timelineEntries = buildAgentRunTimelineEntries(messages);
  if (timelineEntries.isEmpty) {
    return null;
  }
  final latestEntry = timelineEntries.first;
  final group = latestEntry.group;
  if (group == null || group.toolCount == 0) {
    return null;
  }
  return _CompletedAgentToolRun(
    taskId: group.taskId,
    messages: group.processMessagesNewestFirst
        .where(_isVisibleAgentToolSummaryMessage)
        .toList(growable: false),
  );
}

class _CompletedAgentToolRun {
  const _CompletedAgentToolRun({required this.taskId, required this.messages});

  final String taskId;
  final List<ChatMessageModel> messages;
}

String resolveAgentToolTitle(Map<String, dynamic> cardData, {Locale? locale}) {
  final activityKind = tool_policy.AgentToolCardPolicy.activityKindFor(
    cardData,
  );
  if (activityKind == tool_policy.AgentToolActivityKind.vlm) {
    final args = _decodeJsonMap(
      _firstNonBlank(<Object?>[cardData['argsJson'], cardData['args']]),
    );
    final action = tool_policy.AgentToolCardPolicy.actionFor(
      activityKind!,
      cardData,
      args,
    );
    final target = tool_policy.AgentToolCardPolicy.targetFor(
      activityKind,
      cardData,
      args,
      action,
    );
    final semanticTitle = tool_policy.AgentToolCardPolicy.semanticTitleForStep(
      kind: activityKind,
      title: _firstNonBlank(<Object?>[
        cardData[kAgentToolTitleField],
        cardData['tool_title'],
        cardData['summary'],
        cardData['progress'],
        cardData['displayName'],
        cardData['toolName'],
      ]),
      action: action,
      target: target,
      cardData: cardData,
      args: args,
      isCurrent:
          tool_policy.AgentToolCardPolicy.normalizeStatus(cardData['status']) ==
          'running',
    );
    if (semanticTitle.isNotEmpty &&
        !tool_policy.AgentToolCardPolicy.isPlaceholderText(semanticTitle)) {
      return _compactToolText(semanticTitle, maxChars: _kToolCardTitleMaxChars);
    }
  }

  final explicit = (cardData[kAgentToolTitleField] ?? '').toString().trim();
  if (explicit.isNotEmpty &&
      !tool_policy.AgentToolCardPolicy.isPlaceholderText(explicit)) {
    return _localizeToolUiText(
      _compactToolText(explicit, maxChars: _kToolCardTitleMaxChars),
      locale: locale,
    );
  }

  final fromArgs = _extractToolTitleFromArgs(
    (cardData['argsJson'] ?? '').toString(),
  );
  if (fromArgs.isNotEmpty &&
      !tool_policy.AgentToolCardPolicy.isPlaceholderText(fromArgs)) {
    return _localizeToolUiText(
      _compactToolText(fromArgs, maxChars: _kToolCardTitleMaxChars),
      locale: locale,
    );
  }

  final prettifiedDisplayName = _prettifyToolName(
    (cardData['displayName'] ?? '').toString(),
  );
  final prettifiedToolName = _prettifyToolName(
    (cardData['toolName'] ?? '').toString(),
  );
  final baseTitle = prettifiedDisplayName.isNotEmpty
      ? prettifiedDisplayName
      : prettifiedToolName;
  if (baseTitle.isNotEmpty) {
    final serverName = (cardData['serverName'] ?? '').toString().trim();
    if ((cardData['toolType'] ?? '').toString() == 'mcp' &&
        serverName.isNotEmpty) {
      return '${_localizeToolUiText(_compactToolText(baseTitle, maxChars: _kToolCardTitleMaxChars), locale: locale)} · $serverName';
    }
    return _localizeToolUiText(
      _compactToolText(baseTitle, maxChars: _kToolCardTitleMaxChars),
      locale: locale,
    );
  }

  final summary = _firstNonPlaceholder(<Object?>[
    cardData['summary'],
    cardData['progress'],
  ]);
  if (summary.isNotEmpty) {
    return _localizeToolUiText(
      _compactToolText(summary, maxChars: _kToolCardTitleMaxChars),
      locale: locale,
    );
  }
  return _localizeToolUiText('工具调用', locale: locale);
}

String resolveAgentToolTerminalOutput(Map<String, dynamic> cardData) {
  return TerminalOutputUtils.buildDisplayOutput(
    terminalOutput: (cardData['terminalOutput'] ?? '').toString(),
    rawResultJson: (cardData['rawResultJson'] ?? '').toString(),
    resultPreviewJson: (cardData['resultPreviewJson'] ?? '').toString(),
  );
}

String resolveAgentToolPreview(
  Map<String, dynamic> cardData, {
  Locale? locale,
}) {
  if ((cardData['toolType'] ?? '').toString() == 'thinking') {
    final content = _nonPlaceholderText(cardData['thinkingContent']);
    final preview = _firstMeaningfulLine(content);
    if (preview.isNotEmpty) {
      return preview;
    }
  }

  final toolType = (cardData['toolType'] ?? '').toString();
  if (toolType == 'terminal') {
    final output = resolveAgentToolTerminalOutput(cardData).trim();
    if (output.isNotEmpty) {
      final nonEmptyLines = output
          .split('\n')
          .map((line) => line.trimRight())
          .where((line) => line.trim().isNotEmpty)
          .toList(growable: false);
      if (nonEmptyLines.isNotEmpty) {
        return _compactToolText(
          nonEmptyLines.last,
          maxChars: _kToolCardPreviewMaxChars,
        );
      }
      return _compactToolText(output, maxChars: _kToolCardPreviewMaxChars);
    }
  }

  final progress = _nonPlaceholderText(cardData['progress']);
  final summary = _nonPlaceholderText(cardData['summary']);
  final title = resolveAgentToolTitle(cardData, locale: locale);
  if (progress.isNotEmpty && progress != title) {
    return _localizeToolUiText(
      _compactToolText(progress, maxChars: _kToolCardPreviewMaxChars),
      locale: locale,
    );
  }
  if (summary.isNotEmpty && summary != title) {
    return _localizeToolUiText(
      _compactToolText(summary, maxChars: _kToolCardPreviewMaxChars),
      locale: locale,
    );
  }
  return resolveAgentToolStatusLabel(cardData, locale: locale);
}

String resolveAgentToolStatusLabel(
  Map<String, dynamic> cardData, {
  Locale? locale,
}) {
  final explicitStatusLabel = (cardData['statusLabel'] ?? '').toString().trim();
  if (explicitStatusLabel.isNotEmpty) {
    return _localizeToolUiText(explicitStatusLabel, locale: locale);
  }
  final status = tool_policy.AgentToolCardPolicy.normalizeStatus(
    cardData['status'],
  );
  final toolType = (cardData['toolType'] ?? 'builtin').toString();
  final activityKind = tool_policy.AgentToolCardPolicy.activityKindFor(
    cardData,
  );
  if (status == 'timeout') {
    return _localizeToolUiText('超时', locale: locale);
  }
  if (status == 'interrupted') {
    return _localizeToolUiText('中断', locale: locale);
  }
  switch (status) {
    case 'success':
      return _localizeToolUiText('成功', locale: locale);
    case 'error':
      return _localizeToolUiText('失败', locale: locale);
    default:
      if (toolType == 'terminal') {
        return _localizeToolUiText('运行中', locale: locale);
      }
      if (toolType == 'thinking') {
        return AppTextLocalizer.choose(
          en: 'Thinking',
          zh: '思考中',
          locale: locale,
        );
      }
      if (toolType == 'browser') {
        return _localizeToolUiText('浏览中', locale: locale);
      }
      if (toolType == 'research') {
        return AppTextLocalizer.choose(
          en: 'Searching',
          zh: '搜索中',
          locale: locale,
        );
      }
      if (toolType == 'vlm' ||
          toolType == 'mobile' ||
          activityKind == tool_policy.AgentToolActivityKind.vlm) {
        return AppTextLocalizer.choose(
          en: 'Running',
          zh: '执行中',
          locale: locale,
        );
      }
      if (toolType == 'workbench') {
        return AppTextLocalizer.choose(
          en: 'Updating',
          zh: '处理中',
          locale: locale,
        );
      }
      if (toolType == 'mcp') {
        return _localizeToolUiText('响应中', locale: locale);
      }
      if (toolType == 'memory') {
        return _localizeToolUiText('处理中', locale: locale);
      }
      return _localizeToolUiText('执行中', locale: locale);
  }
}

String resolveAgentToolTypeLabel(
  Map<String, dynamic> cardData, {
  Locale? locale,
}) {
  final explicitTypeLabel = (cardData['toolTypeLabel'] ?? '').toString().trim();
  if (explicitTypeLabel.isNotEmpty) {
    return _localizeToolUiText(explicitTypeLabel, locale: locale);
  }
  final activityKind = tool_policy.AgentToolCardPolicy.activityKindFor(
    cardData,
  );
  if (activityKind == tool_policy.AgentToolActivityKind.vlm) {
    return AppTextLocalizer.choose(
      en: 'Visual task',
      zh: '视觉执行',
      locale: locale,
    );
  }
  switch ((cardData['toolType'] ?? '').toString()) {
    case 'terminal':
      return _localizeToolUiText('终端', locale: locale);
    case 'thinking':
      return AppTextLocalizer.choose(en: 'Thinking', zh: '思考', locale: locale);
    case 'browser':
      return _localizeToolUiText('浏览器', locale: locale);
    case 'research':
      return AppTextLocalizer.choose(
        en: 'Web search',
        zh: '网页搜索',
        locale: locale,
      );
    case 'vlm':
    case 'mobile':
      return AppTextLocalizer.choose(
        en: 'Visual task',
        zh: '视觉执行',
        locale: locale,
      );
    case 'workspace':
      return _localizeToolUiText('工作区', locale: locale);
    case 'oob_function':
    case 'reusable_function':
      return AppTextLocalizer.choose(
        en: 'Reusable command',
        zh: '复用指令',
        locale: locale,
      );
    case 'workbench':
      return AppTextLocalizer.choose(
        en: 'Workbench',
        zh: '工作台',
        locale: locale,
      );
    case 'schedule':
      return _localizeToolUiText('定时', locale: locale);
    case 'alarm':
      return _localizeToolUiText('提醒', locale: locale);
    case 'calendar':
      return _localizeToolUiText('日历', locale: locale);
    case 'memory':
      return _localizeToolUiText('记忆', locale: locale);
    case 'skill':
      return 'Skill';
    case 'subagent':
      return _localizeToolUiText('子任务', locale: locale);
    case 'mcp':
      return 'MCP';
    default:
      return _localizeToolUiText('工具调用', locale: locale);
  }
}

String buildAgentToolTranscript(
  List<Map<String, dynamic>> cards, {
  int maxTotalLines = 40,
  int maxTerminalLinesPerTool = 10,
}) {
  if (cards.isEmpty) {
    return '';
  }

  final transcriptLines = <String>[];
  for (final card in cards.reversed) {
    final title = resolveAgentToolTitle(card);
    transcriptLines.add('\$ $title');

    if ((card['toolType'] ?? '').toString() == 'terminal') {
      final output = resolveAgentToolTerminalOutput(card).trimRight();
      if (output.isNotEmpty) {
        final lines = output.split('\n');
        final start = lines.length > maxTerminalLinesPerTool
            ? lines.length - maxTerminalLinesPerTool
            : 0;
        transcriptLines.addAll(lines.sublist(start));
      } else {
        transcriptLines.add('> ${resolveAgentToolPreview(card)}');
      }
    } else {
      transcriptLines.add(
        '> ${resolveAgentToolTypeLabel(card)} · ${resolveAgentToolPreview(card)}',
      );
    }
    transcriptLines.add('');
  }

  if (transcriptLines.isEmpty) {
    return '';
  }

  var normalized = transcriptLines.join('\n').trimRight();
  if (maxTotalLines > 0) {
    final lines = normalized.split('\n');
    if (lines.length > maxTotalLines) {
      normalized = [
        AppTextLocalizer.text('[更早记录已省略]'),
        ...lines.sublist(lines.length - maxTotalLines),
      ].join('\n');
    }
  }
  return normalized;
}

String _extractToolTitleFromArgs(String argsJson) {
  return (tool_policy.AgentToolCardPolicy.decodeJsonMap(
            argsJson,
          )['tool_title'] ??
          '')
      .toString()
      .trim();
}

String _firstNonPlaceholder(Iterable<Object?> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty &&
        !tool_policy.AgentToolCardPolicy.isPlaceholderText(text)) {
      return text;
    }
  }
  return '';
}

String _nonPlaceholderText(Object? value) {
  final text = value?.toString().trim() ?? '';
  return tool_policy.AgentToolCardPolicy.isPlaceholderText(text) ? '' : text;
}

String _compactToolText(String value, {required int maxChars}) {
  final normalized = value
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split('\n')
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .join(' · ')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  if (normalized.length <= maxChars) {
    return normalized;
  }
  return '${normalized.substring(0, maxChars - 1).trimRight()}…';
}

String _prettifyToolName(String raw) {
  final text = raw.trim();
  if (text.isEmpty) {
    return '';
  }
  final normalized = text
      .replaceAll(RegExp(r'[_\-]+'), ' ')
      .replaceAllMapped(
        RegExp(r'([a-z0-9])([A-Z])'),
        (match) => '${match.group(1)} ${match.group(2)}',
      )
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  if (normalized.isEmpty) {
    return '';
  }
  final lower = normalized.toLowerCase();
  if (lower == 'call function' || lower == 'omniflow.call function') {
    return 'Reusable command';
  }
  if (lower == 'calltool' || lower == 'call tool') {
    return 'Tool call';
  }
  if (lower == 'vlm task' || lower == 'vlm') {
    return 'Visual task';
  }
  return normalized;
}

String _localizeToolUiText(String value, {Locale? locale}) {
  final text = value.trim();
  if (text.isEmpty) {
    return text;
  }
  switch (text) {
    case 'Thinking':
    case '思考':
      return AppTextLocalizer.choose(en: 'Thinking', zh: '思考', locale: locale);
    case 'Thought':
      return AppTextLocalizer.choose(en: 'Thought', zh: '思考', locale: locale);
    case 'Done':
    case '已完成':
    case '完成':
    case '成功':
      return AppTextLocalizer.choose(en: 'Done', zh: '已完成', locale: locale);
    case 'Running':
    case '运行中':
    case '执行中':
      return AppTextLocalizer.choose(en: 'Running', zh: '运行中', locale: locale);
    case 'Thinking...':
    case '思考中':
      return AppTextLocalizer.choose(en: 'Thinking', zh: '思考中', locale: locale);
    case 'Tool activity':
    case 'Tool call':
    case 'Tool Call':
    case '工具':
    case '工具调用':
      return AppTextLocalizer.choose(
        en: 'Tool call',
        zh: '工具调用',
        locale: locale,
      );
    case 'Browser activity':
    case '浏览器操作':
      return AppTextLocalizer.choose(
        en: 'Browser activity',
        zh: '浏览器操作',
        locale: locale,
      );
    case 'Browser':
    case '浏览器':
      return AppTextLocalizer.choose(en: 'Browser', zh: '浏览器', locale: locale);
    case 'Research activity':
    case 'Web search':
    case '网页搜索':
      return AppTextLocalizer.choose(
        en: 'Web search',
        zh: '网页搜索',
        locale: locale,
      );
    case 'Visual task':
    case 'Visual Task':
    case 'Device action':
    case '视觉执行':
    case '手机操作':
      return AppTextLocalizer.choose(
        en: 'Visual task',
        zh: '视觉执行',
        locale: locale,
      );
    case 'Terminal activity':
    case '终端操作':
      return AppTextLocalizer.choose(
        en: 'Terminal activity',
        zh: '终端操作',
        locale: locale,
      );
    case 'Terminal':
    case '终端':
      return AppTextLocalizer.choose(en: 'Terminal', zh: '终端', locale: locale);
    case 'Workspace activity':
    case '工作区操作':
      return AppTextLocalizer.choose(
        en: 'Workspace activity',
        zh: '工作区操作',
        locale: locale,
      );
    case 'Workspace':
    case '工作区':
      return AppTextLocalizer.choose(
        en: 'Workspace',
        zh: '工作区',
        locale: locale,
      );
    case 'Workbench activity':
    case '工作台操作':
      return AppTextLocalizer.choose(
        en: 'Workbench activity',
        zh: '工作台操作',
        locale: locale,
      );
    case 'Workbench':
    case '工作台':
      return AppTextLocalizer.choose(
        en: 'Workbench',
        zh: '工作台',
        locale: locale,
      );
    case 'Reusable Command':
    case 'Reusable command':
    case '复用指令':
      return AppTextLocalizer.choose(
        en: 'Reusable command',
        zh: '复用指令',
        locale: locale,
      );
    case 'MCP activity':
    case 'MCP 操作':
      return AppTextLocalizer.choose(
        en: 'MCP activity',
        zh: 'MCP 操作',
        locale: locale,
      );
    case '失败':
      return AppTextLocalizer.choose(en: 'Failed', zh: '失败', locale: locale);
    case '超时':
      return AppTextLocalizer.choose(en: 'Timed out', zh: '超时', locale: locale);
    case '中断':
      return AppTextLocalizer.choose(
        en: 'Interrupted',
        zh: '中断',
        locale: locale,
      );
    case '浏览中':
      return AppTextLocalizer.choose(en: 'Browsing', zh: '浏览中', locale: locale);
    case '搜索中':
      return AppTextLocalizer.choose(
        en: 'Searching',
        zh: '搜索中',
        locale: locale,
      );
    case '处理中':
      return AppTextLocalizer.choose(
        en: 'Processing',
        zh: '处理中',
        locale: locale,
      );
    case '响应中':
      return AppTextLocalizer.choose(
        en: 'Responding',
        zh: '响应中',
        locale: locale,
      );
    case '定时':
      return AppTextLocalizer.choose(en: 'Schedule', zh: '定时', locale: locale);
    case '提醒':
      return AppTextLocalizer.choose(en: 'Reminder', zh: '提醒', locale: locale);
    case '日历':
      return AppTextLocalizer.choose(en: 'Calendar', zh: '日历', locale: locale);
    case '记忆':
      return AppTextLocalizer.choose(en: 'Memory', zh: '记忆', locale: locale);
    case '子任务':
      return AppTextLocalizer.choose(en: 'Subtask', zh: '子任务', locale: locale);
  }
  return text;
}

String _firstMeaningfulLine(String value) {
  final line = value
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split('\n')
      .map((item) => item.replaceAll(RegExp(r'\s+'), ' ').trim())
      .firstWhere((item) => item.isNotEmpty, orElse: () => '');
  if (line.length <= _kToolCardPreviewMaxChars) {
    return line;
  }
  return '${line.substring(0, _kToolCardPreviewMaxChars - 1).trimRight()}…';
}
