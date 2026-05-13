import 'dart:convert';

import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';

const String kAgentToolSummaryCardType = 'agent_tool_summary';
const String kAgentToolTitleField = 'toolTitle';
const int _kToolCardTitleMaxChars = 80;
const int _kToolCardPreviewMaxChars = 160;

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
  return messages
      .map((message) => message.cardData)
      .whereType<Map<String, dynamic>>()
      .where(
        (cardData) =>
            (cardData['type'] ?? '').toString() == kAgentToolSummaryCardType,
      )
      .toList(growable: false);
}

List<Map<String, dynamic>> extractRunningAgentToolCards(
  List<ChatMessageModel> messages,
) {
  return extractAgentToolCards(messages)
      .where((cardData) => (cardData['status'] ?? '').toString() == 'running')
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
        .where(_isAgentToolSummaryMessage)
        .toList(growable: false);
  }
  return const <ChatMessageModel>[];
}

String? resolveAgentToolTaskId(ChatMessageModel message) {
  final fromCard = (message.cardData?['taskId'] ?? '').toString().trim();
  if (fromCard.isNotEmpty) {
    return fromCard;
  }
  final fromStream = (message.streamMeta?['parentTaskId'] ?? '')
      .toString()
      .trim();
  return fromStream.isEmpty ? null : fromStream;
}

Map<String, dynamic>? resolveActiveAgentToolCard(
  List<Map<String, dynamic>> cards,
) {
  for (final card in cards) {
    if ((card['status'] ?? '').toString() == 'running') {
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
  final activeTool = _asStringKeyMap(run['activeTool']);
  if (activeTool.isEmpty) {
    return null;
  }
  final extras = _asStringKeyMap(activeTool['extras']);
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

  final cardId = (card['cardId'] ?? '').toString().trim();
  if (cardId.isEmpty) {
    final toolCallId = (card['toolCallId'] ?? '').toString().trim();
    if (toolCallId.isNotEmpty) {
      card['cardId'] = toolCallId;
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

  return card;
}

bool _isAgentToolSummaryMessage(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString() ==
      kAgentToolSummaryCardType;
}

Map<String, dynamic> _asStringKeyMap(dynamic value) {
  if (value is! Map) {
    return const <String, dynamic>{};
  }
  return value.map((key, item) => MapEntry(key.toString(), item));
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
        .where(_isAgentToolSummaryMessage)
        .toList(growable: false),
  );
}

class _CompletedAgentToolRun {
  const _CompletedAgentToolRun({required this.taskId, required this.messages});

  final String taskId;
  final List<ChatMessageModel> messages;
}

String resolveAgentToolTitle(Map<String, dynamic> cardData) {
  final explicit = (cardData[kAgentToolTitleField] ?? '').toString().trim();
  if (explicit.isNotEmpty) {
    return AppTextLocalizer.text(
      _compactToolText(explicit, maxChars: _kToolCardTitleMaxChars),
    );
  }

  final fromArgs = _extractToolTitleFromArgs(
    (cardData['argsJson'] ?? '').toString(),
  );
  if (fromArgs.isNotEmpty) {
    return AppTextLocalizer.text(
      _compactToolText(fromArgs, maxChars: _kToolCardTitleMaxChars),
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
      return '${AppTextLocalizer.text(_compactToolText(baseTitle, maxChars: _kToolCardTitleMaxChars))} · $serverName';
    }
    return AppTextLocalizer.text(
      _compactToolText(baseTitle, maxChars: _kToolCardTitleMaxChars),
    );
  }

  final summary = (cardData['summary'] ?? '').toString().trim();
  if (summary.isNotEmpty) {
    return AppTextLocalizer.text(
      _compactToolText(summary, maxChars: _kToolCardTitleMaxChars),
    );
  }
  return AppTextLocalizer.text('工具调用');
}

String resolveAgentToolTerminalOutput(Map<String, dynamic> cardData) {
  return TerminalOutputUtils.buildDisplayOutput(
    terminalOutput: (cardData['terminalOutput'] ?? '').toString(),
    rawResultJson: (cardData['rawResultJson'] ?? '').toString(),
    resultPreviewJson: (cardData['resultPreviewJson'] ?? '').toString(),
  );
}

String resolveAgentToolPreview(Map<String, dynamic> cardData) {
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

  final progress = (cardData['progress'] ?? '').toString().trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  final title = resolveAgentToolTitle(cardData);
  if (progress.isNotEmpty && progress != title) {
    return AppTextLocalizer.text(
      _compactToolText(progress, maxChars: _kToolCardPreviewMaxChars),
    );
  }
  if (summary.isNotEmpty && summary != title) {
    return AppTextLocalizer.text(
      _compactToolText(summary, maxChars: _kToolCardPreviewMaxChars),
    );
  }
  return resolveAgentToolStatusLabel(cardData);
}

String resolveAgentToolStatusLabel(Map<String, dynamic> cardData) {
  final explicitStatusLabel = (cardData['statusLabel'] ?? '').toString().trim();
  if (explicitStatusLabel.isNotEmpty) {
    return AppTextLocalizer.text(explicitStatusLabel);
  }
  final status = (cardData['status'] ?? 'running').toString();
  final toolType = (cardData['toolType'] ?? 'builtin').toString();
  if (status == 'timeout') {
    return AppTextLocalizer.text('超时');
  }
  if (status == 'interrupted') {
    return AppTextLocalizer.text('中断');
  }
  switch (status) {
    case 'success':
      return AppTextLocalizer.text('成功');
    case 'error':
      return AppTextLocalizer.text('失败');
    default:
      if (toolType == 'terminal') return AppTextLocalizer.text('运行中');
      if (toolType == 'browser') return AppTextLocalizer.text('浏览中');
      if (toolType == 'workbench') {
        return AppTextLocalizer.choose(en: 'Updating', zh: '处理中');
      }
      if (toolType == 'mcp') return AppTextLocalizer.text('响应中');
      if (toolType == 'memory') return AppTextLocalizer.text('处理中');
      return AppTextLocalizer.text('执行中');
  }
}

String resolveAgentToolTypeLabel(Map<String, dynamic> cardData) {
  final explicitTypeLabel = (cardData['toolTypeLabel'] ?? '').toString().trim();
  if (explicitTypeLabel.isNotEmpty) {
    return AppTextLocalizer.text(explicitTypeLabel);
  }
  switch ((cardData['toolType'] ?? '').toString()) {
    case 'terminal':
      return AppTextLocalizer.text('终端');
    case 'browser':
      return AppTextLocalizer.text('浏览器');
    case 'workspace':
      return AppTextLocalizer.text('工作区');
    case 'workbench':
      return AppTextLocalizer.choose(en: 'Workbench', zh: '工作台');
    case 'schedule':
      return AppTextLocalizer.text('定时');
    case 'alarm':
      return AppTextLocalizer.text('提醒');
    case 'calendar':
      return AppTextLocalizer.text('日历');
    case 'memory':
      return AppTextLocalizer.text('记忆');
    case 'skill':
      return 'Skill';
    case 'subagent':
      return AppTextLocalizer.text('子任务');
    case 'mcp':
      return 'MCP';
    default:
      return AppTextLocalizer.text('工具');
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
  final text = argsJson.trim();
  if (text.isEmpty) {
    return '';
  }
  try {
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      return '';
    }
    return (decoded['tool_title'] ?? '').toString().trim();
  } catch (_) {
    return '';
  }
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
  if (lower == 'calltool' || lower == 'call tool') {
    return AppTextLocalizer.choose(en: 'Tool Call', zh: '工具调用');
  }
  return normalized;
}
