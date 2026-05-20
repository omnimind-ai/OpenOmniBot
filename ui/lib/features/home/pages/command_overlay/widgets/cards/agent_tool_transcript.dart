import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

const BorderRadius _kTranscriptSurfaceRadius = BorderRadius.all(
  Radius.circular(8),
);
const double _kToolDetailFontSize = 12;
const ValueKey<String> kAgentToolDetailSheetKey = ValueKey<String>(
  'agent-tool-detail-sheet',
);
const ValueKey<String> kAgentToolDetailCopyButtonKey = ValueKey<String>(
  'agent-tool-detail-copy',
);
const ValueKey<String> kAgentToolDetailOutputPanelKey = ValueKey<String>(
  'agent-tool-detail-output-panel',
);

enum AgentToolTranscriptTextStyle { plain, monospace }

class AgentToolTranscript {
  const AgentToolTranscript({
    required this.inputText,
    required this.resultText,
    required this.previewText,
    this.resultTextStyle = AgentToolTranscriptTextStyle.plain,
  });

  final String inputText;
  final String resultText;
  final String previewText;
  final AgentToolTranscriptTextStyle resultTextStyle;

  String get promptLine => inputText;
  String get outputText => resultText;
  bool get resultIsMonospace =>
      resultTextStyle == AgentToolTranscriptTextStyle.monospace;
}

AgentToolTranscript buildAgentToolTranscript(
  Map<String, dynamic> cardData, {
  int maxOutputLines = 28,
  int maxPreviewLines = 2,
  int maxPreviewChars = 220,
  Locale? locale,
}) {
  final toolType = (cardData['toolType'] ?? '').toString().trim();
  final usesTerminalPayload = toolType == 'terminal';
  final promptLine = usesTerminalPayload
      ? _buildTerminalPromptLine(cardData)
      : _buildToolPromptLine(cardData);
  final outputText = usesTerminalPayload
      ? _buildTerminalOutputText(cardData)
      : _buildStructuredOutputText(
          cardData,
          maxOutputLines: maxOutputLines,
          locale: locale,
        );
  final previewText = _buildPreviewText(
    outputText,
    preferTail: usesTerminalPayload,
    maxLines: maxPreviewLines,
    maxChars: maxPreviewChars,
  );

  return AgentToolTranscript(
    inputText: promptLine,
    resultText: outputText,
    previewText: previewText,
    resultTextStyle: usesTerminalPayload
        ? AgentToolTranscriptTextStyle.monospace
        : AgentToolTranscriptTextStyle.plain,
  );
}

Future<void> showAgentToolDetailDialog(
  BuildContext context, {
  required Map<String, dynamic> cardData,
}) {
  return showDialog<void>(
    context: context,
    useRootNavigator: false,
    builder: (dialogContext) {
      final palette = dialogContext.omniPalette;
      return Dialog(
        elevation: 0,
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 30),
        child: Container(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(dialogContext).size.height * 0.76,
            maxWidth: 520,
          ),
          decoration: BoxDecoration(
            color: palette.surfacePrimary,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: palette.borderSubtle),
            boxShadow: [
              BoxShadow(
                color: palette.shadowColor.withValues(alpha: 0.24),
                blurRadius: 26,
                offset: const Offset(0, 14),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(18),
            child: Material(
              color: palette.surfacePrimary,
              child: _AgentToolDetailContent(
                cardData: cardData,
                headerPadding: const EdgeInsets.fromLTRB(18, 14, 18, 10),
                bodyPadding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
              ),
            ),
          ),
        ),
      );
    },
  );
}

Future<void> showAgentToolDetailSheet(
  BuildContext context, {
  required Map<String, dynamic> cardData,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    isDismissible: true,
    enableDrag: false,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (sheetContext) {
      return _AgentToolDetailSheetFrame(cardData: cardData);
    },
  );
}

Color resolveAgentToolStatusColor(String status) {
  return AgentToolCardPolicy.statusColor(status);
}

IconData resolveAgentToolStatusIcon(String status, String toolType) {
  return AgentToolCardPolicy.statusIcon(status, toolType);
}

TextSpan _buildOutputTextSpan(String outputText, TextStyle outputStyle) {
  return AnsiTextSpanBuilder.build(outputText, outputStyle);
}

String _buildTerminalPromptLine(Map<String, dynamic> cardData) {
  final args = _decodeJsonMap((cardData['argsJson'] ?? '').toString());
  final toolName = (cardData['toolName'] ?? '').toString().trim();
  final workingDirectory = (args['workingDirectory'] ?? args['cwd'] ?? '')
      .toString()
      .trim();
  final command = (args['command'] ?? '').toString().trim();

  if (command.isNotEmpty) {
    if (workingDirectory.isEmpty) {
      return '\$ $command';
    }
    return '\$ cd ${_quoteShellValue(workingDirectory)} && $command';
  }

  if (toolName == 'terminal_session_start') {
    if (workingDirectory.isNotEmpty) {
      return '\$ cd ${_quoteShellValue(workingDirectory)}';
    }
    return '\$ sh';
  }
  if (toolName == 'terminal_session_stop') {
    return '\$ exit';
  }
  if (toolName == 'terminal_session_read') {
    final sessionId = (args['sessionId'] ?? cardData['terminalSessionId'] ?? '')
        .toString()
        .trim();
    return sessionId.isEmpty
        ? '\$ tail -f session.log'
        : '\$ tail -f $sessionId';
  }

  return _buildToolPromptLine(cardData);
}

String _buildToolPromptLine(Map<String, dynamic> cardData) {
  final toolName = (cardData['toolName'] ?? '').toString().trim().isEmpty
      ? (cardData['displayName'] ?? 'tool').toString().trim()
      : (cardData['toolName'] ?? '').toString().trim();
  final args = _decodeJsonMap((cardData['argsJson'] ?? '').toString());
  final segments = <String>[toolName];

  for (final entry in args.entries) {
    final key = entry.key.trim();
    if (key.isEmpty || key == 'tool_title' || key == 'toolTitle') {
      continue;
    }
    segments.addAll(_formatCliArguments(key, entry.value));
  }

  return '\$ ${segments.join(' ').trim()}';
}

String _buildTerminalOutputText(Map<String, dynamic> cardData) {
  final output = resolveAgentToolTerminalOutput(cardData).trimRight();
  if (output.isNotEmpty) {
    return output;
  }

  final status = (cardData['status'] ?? '').toString().trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  final progress = (cardData['progress'] ?? '').toString().trim();
  final fallback = progress.isNotEmpty ? progress : summary;
  if (status == 'running' && _isGenericTerminalProgressMessage(fallback)) {
    return '';
  }
  return fallback;
}

String _buildStructuredOutputText(
  Map<String, dynamic> cardData, {
  required int maxOutputLines,
  Locale? locale,
}) {
  final status = (cardData['status'] ?? '').toString().trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  final progress = (cardData['progress'] ?? '').toString().trim();
  final previewMap = _decodeJsonMap(
    (cardData['resultPreviewJson'] ?? '').toString(),
  );
  final rawMap = _decodeJsonMap((cardData['rawResultJson'] ?? '').toString());
  final lines = <String>[];

  if (status == 'running') {
    _appendUniqueLine(lines, progress.isNotEmpty ? progress : summary);
  } else if (status == 'timeout' ||
      status == 'error' ||
      status == 'interrupted') {
    _appendUniqueLine(lines, summary);
  }

  final structuredPreview = _buildStructuredLines(
    previewMap,
    maxLines: maxOutputLines,
  );
  if (structuredPreview.isNotEmpty) {
    lines.addAll(structuredPreview.where((line) => !lines.contains(line)));
  } else {
    final structuredRaw = _buildStructuredLines(
      rawMap,
      maxLines: maxOutputLines,
    );
    lines.addAll(structuredRaw.where((line) => !lines.contains(line)));
  }

  if (lines.isEmpty) {
    _appendUniqueLine(lines, progress);
    _appendUniqueLine(lines, summary);
    if (lines.isEmpty) {
      lines.add(resolveAgentToolStatusLabel(cardData, locale: locale));
    }
  }

  final normalized = lines.join('\n').trim();
  return _trimStructuredOutput(normalized, maxLines: maxOutputLines);
}

String _buildPreviewText(
  String outputText, {
  required bool preferTail,
  required int maxLines,
  required int maxChars,
}) {
  final lines = outputText
      .split('\n')
      .map((line) => line.trimRight())
      .where((line) => line.trim().isNotEmpty)
      .toList(growable: false);
  if (lines.isEmpty) {
    return '';
  }
  final selected = preferTail
      ? lines.sublist(math.max(0, lines.length - maxLines))
      : lines.take(maxLines).toList(growable: false);
  final preview = selected.join('\n');
  if (preview.length <= maxChars) {
    return preview;
  }
  return '${preview.substring(0, maxChars - 1).trimRight()}…';
}

List<String> _formatCliArguments(String key, dynamic value) {
  final flag = '--$key';
  if (value == null) {
    return const <String>[];
  }
  if (value is bool) {
    return value ? <String>[flag] : <String>['$flag=false'];
  }
  if (value is num) {
    return <String>[flag, value.toString()];
  }
  if (value is String) {
    final trimmed = value.trim();
    if (trimmed.isEmpty) {
      return const <String>[];
    }
    return <String>[flag, _quoteShellValue(trimmed)];
  }
  if (value is List) {
    final segments = <String>[];
    for (final item in value) {
      if (item == null) {
        continue;
      }
      if (item is Map || item is List) {
        segments.addAll(<String>[flag, _quoteShellValue(jsonEncode(item))]);
        continue;
      }
      final itemText = item.toString().trim();
      if (itemText.isEmpty) {
        continue;
      }
      segments.addAll(<String>[flag, _quoteShellValue(itemText)]);
    }
    return segments;
  }
  return <String>[flag, _quoteShellValue(jsonEncode(value))];
}

List<String> _buildStructuredLines(
  Map<String, dynamic> source, {
  required int maxLines,
}) {
  if (source.isEmpty || maxLines <= 0) {
    return const <String>[];
  }

  final lines = <String>[];

  bool canAdd() => lines.length < maxLines;

  void addLine(String line) {
    final normalized = line.trimRight();
    if (normalized.isEmpty || lines.contains(normalized) || !canAdd()) {
      return;
    }
    lines.add(normalized);
  }

  void appendValue(String label, dynamic value, int depth) {
    if (!canAdd() || value == null) {
      return;
    }

    if (value is Map) {
      final normalizedMap = value.map(
        (key, nested) => MapEntry(key.toString(), nested),
      );
      final summary = _summarizeMap(normalizedMap);
      if (summary != null && summary.isNotEmpty) {
        addLine(label.isEmpty ? summary : '$label: $summary');
        return;
      }
      for (final entry in _prioritizeEntries(normalizedMap.entries)) {
        final key = entry.key.trim();
        if (_shouldSkipStructuredKey(key)) {
          continue;
        }
        final nextLabel = label.isEmpty ? key : '$label.$key';
        appendValue(nextLabel, entry.value, depth + 1);
        if (!canAdd()) {
          return;
        }
      }
      return;
    }

    if (value is List) {
      if (value.isEmpty) {
        return;
      }
      if (_canInlineScalarList(value)) {
        addLine('$label: ${value.map(_formatInlineValue).join(', ')}');
        return;
      }
      final itemLimit = math.min(value.length, depth <= 1 ? 5 : 3);
      for (var index = 0; index < itemLimit; index++) {
        final item = value[index];
        if (item is Map) {
          final normalizedMap = item.map(
            (key, nested) => MapEntry(key.toString(), nested),
          );
          final summary = _summarizeMap(normalizedMap);
          if (summary != null && summary.isNotEmpty) {
            addLine('$label[$index]: $summary');
          } else {
            appendValue('$label[$index]', normalizedMap, depth + 1);
          }
        } else {
          final formatted = _formatScalarLine(item);
          if (formatted != null) {
            addLine('$label[$index]: $formatted');
          }
        }
        if (!canAdd()) {
          return;
        }
      }
      if (value.length > itemLimit && canAdd()) {
        addLine('$label: ... +${value.length - itemLimit} more');
      }
      return;
    }

    final formatted = _formatScalarLine(value);
    if (formatted != null) {
      addLine(label.isEmpty ? formatted : '$label: $formatted');
    }
  }

  for (final entry in _prioritizeEntries(source.entries)) {
    final key = entry.key.trim();
    if (_shouldSkipStructuredKey(key)) {
      continue;
    }
    appendValue(key, entry.value, 0);
    if (!canAdd()) {
      break;
    }
  }

  return lines;
}

List<MapEntry<String, dynamic>> _prioritizeEntries(
  Iterable<MapEntry<String, dynamic>> entries,
) {
  const priority = <String, int>{
    'message': 0,
    'question': 1,
    'errorMessage': 2,
    'path': 3,
    'targetPath': 4,
    'query': 5,
    'url': 6,
    'currentUrl': 7,
    'count': 8,
    'name': 9,
    'title': 10,
    'taskId': 11,
    'goal': 12,
    'content': 13,
    'snippet': 14,
    'items': 15,
  };
  final sorted = entries.toList(growable: false);
  sorted.sort((left, right) {
    final leftRank = priority[left.key] ?? 99;
    final rightRank = priority[right.key] ?? 99;
    if (leftRank != rightRank) {
      return leftRank.compareTo(rightRank);
    }
    return left.key.compareTo(right.key);
  });
  return sorted;
}

String? _summarizeMap(Map<String, dynamic> value) {
  final parts = <String>[];
  final path = _firstNonBlank(value, const [
    'path',
    'targetPath',
    'sourcePath',
  ]);
  final name = _firstNonBlank(value, const ['name', 'title', 'label', 'id']);
  final url = _firstNonBlank(value, const ['currentUrl', 'url']);
  final matchType = (value['matchType'] ?? '').toString().trim();
  final snippet = _firstNonBlank(value, const [
    'snippet',
    'content',
    'message',
  ]);

  if (name.isNotEmpty) {
    parts.add(name);
  }
  if (path.isNotEmpty && !parts.contains(path)) {
    parts.add(path);
  }
  if (url.isNotEmpty && !parts.contains(url)) {
    parts.add(url);
  }
  if (matchType.isNotEmpty) {
    parts.add(matchType);
  }
  if (value['isDirectory'] == true && !parts.contains('dir')) {
    parts.add('dir');
  }
  final sizeValue = value['size'];
  if (sizeValue is num && sizeValue > 0) {
    parts.add(_formatBytes(sizeValue.toInt()));
  }
  if (snippet.isNotEmpty) {
    parts.add(_truncateInline(snippet));
  }

  if (parts.isEmpty) {
    return null;
  }
  return parts.join(' | ');
}

String _trimStructuredOutput(
  String value, {
  required int maxLines,
  int maxChars = 6000,
}) {
  if (value.isEmpty) {
    return value;
  }
  var candidate = value;
  if (candidate.length > maxChars) {
    candidate = candidate.substring(0, maxChars).trimRight();
    candidate = '$candidate\n...[truncated]';
  }
  final lines = candidate.split('\n');
  if (lines.length > maxLines) {
    candidate = [...lines.take(maxLines), '...[truncated]'].join('\n');
  }
  return candidate.trimRight();
}

bool _canInlineScalarList(List<dynamic> value) {
  if (value.isEmpty || value.any((item) => item is Map || item is List)) {
    return false;
  }
  final rendered = value.map(_formatInlineValue).join(', ');
  return rendered.length <= 120;
}

String _formatInlineValue(dynamic value) {
  if (value == null) {
    return 'null';
  }
  return _truncateInline(value.toString());
}

String? _formatScalarLine(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is bool || value is num) {
    return value.toString();
  }
  final normalized = value.toString().trim();
  if (normalized.isEmpty) {
    return null;
  }
  return _truncateInline(normalized);
}

String _truncateInline(String value, {int maxLength = 140}) {
  final collapsed = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (collapsed.length <= maxLength) {
    return collapsed;
  }
  return '${collapsed.substring(0, maxLength - 1).trimRight()}…';
}

bool _isGenericTerminalProgressMessage(String value) {
  return AgentToolCardPolicy.isGenericTerminalProgressText(value);
}

String _firstNonBlank(Map<String, dynamic> value, List<String> keys) {
  for (final key in keys) {
    final candidate = (value[key] ?? '').toString().trim();
    if (candidate.isNotEmpty) {
      return candidate;
    }
  }
  return '';
}

bool _shouldSkipStructuredKey(String key) {
  final normalized = key.trim();
  if (normalized.isEmpty) {
    return true;
  }
  const exactNoise = <String>{
    'success',
    'summary',
    'toolTitle',
    'tool_title',
    'terminalOutput',
    'terminalOutputLength',
    'stdout',
    'stdoutLength',
    'stderr',
    'stderrLength',
    'rawExtras',
    'artifacts',
    'actions',
    'uri',
    'logUri',
    'androidPath',
    'androidRootPath',
    'androidSkillFilePath',
    'androidSourcePath',
    'androidTargetPath',
    'androidLogPath',
    'liveSessionId',
    'liveStreamState',
    'liveFallbackReason',
    'timedOut',
  };
  if (exactNoise.contains(normalized)) {
    return true;
  }
  final lower = normalized.toLowerCase();
  return lower.contains('html') ||
      lower.contains('trace') ||
      lower.contains('bodymarkdown') ||
      lower.contains('raw');
}

Map<String, dynamic> _decodeJsonMap(String raw) {
  final trimmed = raw.trim();
  if (trimmed.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(trimmed);
    if (decoded is Map) {
      return decoded.map((key, value) => MapEntry(key.toString(), value));
    }
  } catch (_) {
    return const <String, dynamic>{};
  }
  return const <String, dynamic>{};
}

String _quoteShellValue(String value) {
  const safePattern = r'^[A-Za-z0-9_./:@%+=,-]+$';
  if (RegExp(safePattern).hasMatch(value)) {
    return value;
  }
  return "'${value.replaceAll("'", "'\"'\"'")}'";
}

void _appendUniqueLine(List<String> lines, String value) {
  final normalized = value.trim();
  if (normalized.isEmpty || lines.contains(normalized)) {
    return;
  }
  lines.add(normalized);
}

String _formatBytes(int value) {
  if (value >= 1024 * 1024) {
    return '${(value / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  if (value >= 1024) {
    return '${(value / 1024).toStringAsFixed(1)} KB';
  }
  return '$value B';
}

class _AgentToolDetailSheetFrame extends StatefulWidget {
  const _AgentToolDetailSheetFrame({required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<_AgentToolDetailSheetFrame> createState() =>
      _AgentToolDetailSheetFrameState();
}

class _AgentToolDetailSheetFrameState
    extends State<_AgentToolDetailSheetFrame> {
  static const double _minHeightFactor = 0.30;
  static const double _maxHeightFactor = 0.88;

  double? _heightFactor;

  double _initialHeightFactor(BuildContext context) {
    final viewportHeight = MediaQuery.sizeOf(context).height;
    final locale = Localizations.localeOf(context);
    final transcript = buildAgentToolTranscript(
      widget.cardData,
      maxOutputLines: 80,
      maxPreviewLines: 4,
      maxPreviewChars: 420,
      locale: locale,
    );
    final outputLineCount = transcript.outputText
        .split('\n')
        .where((line) => line.trim().isNotEmpty)
        .length;
    final commandLineBudget = math.min(
      4,
      math.max(1, (transcript.promptLine.length / 42).ceil()),
    );

    var factor = viewportHeight < 720 ? 0.50 : 0.38;
    if (commandLineBudget >= 3 || outputLineCount > 8) {
      factor += 0.05;
    }
    if (outputLineCount > 24) {
      factor += 0.10;
    }
    if (outputLineCount > 56) {
      factor += 0.12;
    }
    return factor.clamp(_minHeightFactor, _maxHeightFactor).toDouble();
  }

  void _handleDragUpdate(DragUpdateDetails details, double availableHeight) {
    if (availableHeight <= 0) {
      return;
    }
    final delta = details.primaryDelta ?? details.delta.dy;
    setState(() {
      final current = _heightFactor ?? _initialHeightFactor(context);
      _heightFactor = (current - delta / availableHeight).clamp(
        _minHeightFactor,
        _maxHeightFactor,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final availableHeight = math.max(
      320.0,
      mediaQuery.size.height -
          mediaQuery.padding.top -
          mediaQuery.viewInsets.bottom,
    );
    final heightFactor = _heightFactor ?? _initialHeightFactor(context);
    final palette = context.omniPalette;
    const borderRadius = BorderRadius.vertical(top: Radius.circular(20));

    return SafeArea(
      top: false,
      child: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.only(bottom: mediaQuery.viewInsets.bottom),
        child: SizedBox(
          key: kAgentToolDetailSheetKey,
          height: availableHeight * heightFactor,
          width: double.infinity,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: palette.surfacePrimary,
              borderRadius: borderRadius,
              border: Border(top: BorderSide(color: palette.borderSubtle)),
              boxShadow: [
                BoxShadow(
                  color: palette.shadowColor.withValues(alpha: 0.24),
                  blurRadius: 24,
                  offset: const Offset(0, -8),
                ),
              ],
            ),
            child: ClipRRect(
              borderRadius: borderRadius,
              child: Material(
                color: palette.surfacePrimary,
                child: Column(
                  children: [
                    GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onVerticalDragUpdate: (details) =>
                          _handleDragUpdate(details, availableHeight),
                      child: SizedBox(
                        height: 22,
                        width: double.infinity,
                        child: Center(
                          child: Container(
                            width: 42,
                            height: 4,
                            decoration: BoxDecoration(
                              color: palette.textTertiary.withValues(
                                alpha: 0.32,
                              ),
                              borderRadius: BorderRadius.circular(999),
                            ),
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: _AgentToolDetailContent(
                        cardData: widget.cardData,
                        headerPadding: const EdgeInsets.fromLTRB(18, 0, 18, 10),
                        bodyPadding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentToolDetailContent extends StatelessWidget {
  const _AgentToolDetailContent({
    required this.cardData,
    required this.headerPadding,
    required this.bodyPadding,
  });

  final Map<String, dynamic> cardData;
  final EdgeInsetsGeometry headerPadding;
  final EdgeInsetsGeometry bodyPadding;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final transcript = buildAgentToolTranscript(
      cardData,
      maxOutputLines: 80,
      maxPreviewLines: 4,
      maxPreviewChars: 420,
      locale: locale,
    );
    final title = resolveAgentToolTitle(cardData, locale: locale);
    final typeLabel = resolveAgentToolTypeLabel(cardData, locale: locale);
    final status = (cardData['status'] ?? 'running').toString();
    final statusLabel = resolveAgentToolStatusLabel(cardData, locale: locale);
    final copyText = _buildDetailCopyText(transcript);
    final accentColor = AgentToolCardPolicy.activityColorForCard(cardData);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: headerPadding,
          child: Row(
            children: [
              Container(
                width: 3,
                height: 38,
                decoration: BoxDecoration(
                  color: accentColor,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
              const SizedBox(width: 10),
              _ToolKindGlyph(cardData: cardData, color: accentColor),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: _kToolDetailFontSize,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0,
                        height: 1.2,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: [
                        _DetailMetaPill(label: typeLabel, color: accentColor),
                        _DetailStatusPill(status: status, label: statusLabel),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _CopyTranscriptButton(text: copyText),
            ],
          ),
        ),
        Expanded(
          child: Padding(
            padding: bodyPadding,
            child: _DetailTranscriptPanel(
              inputLabel: AppTextLocalizer.choose(
                zh: '输入',
                en: 'Input',
                locale: locale,
              ),
              resultLabel: AppTextLocalizer.choose(
                zh: '结果',
                en: 'Result',
                locale: locale,
              ),
              emptyResultLabel: AppTextLocalizer.choose(
                zh: '暂无结果',
                en: 'No result yet',
                locale: locale,
              ),
              inputText: transcript.promptLine,
              resultText: transcript.outputText,
              accentColor: accentColor,
              resultTextStyle: transcript.resultTextStyle,
            ),
          ),
        ),
      ],
    );
  }
}

class _ToolKindGlyph extends StatelessWidget {
  const _ToolKindGlyph({required this.cardData, required this.color});

  final Map<String, dynamic> cardData;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final kind =
        AgentToolCardPolicy.activityKindFor(cardData) ??
        AgentToolActivityKind.generic;
    final background = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.16),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.10);
    return Container(
      width: 26,
      height: 26,
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(7),
        border: Border.all(color: color.withValues(alpha: 0.18)),
      ),
      child: Icon(_iconForToolKind(kind), size: 15, color: color),
    );
  }
}

IconData _iconForToolKind(AgentToolActivityKind kind) {
  return switch (kind) {
    AgentToolActivityKind.thinking => Icons.psychology_alt_outlined,
    AgentToolActivityKind.browser => Icons.public_rounded,
    AgentToolActivityKind.research => Icons.travel_explore_rounded,
    AgentToolActivityKind.vlm => Icons.touch_app_rounded,
    AgentToolActivityKind.terminal => Icons.terminal_rounded,
    AgentToolActivityKind.workspace => Icons.folder_open_rounded,
    AgentToolActivityKind.workbench => Icons.dashboard_customize_rounded,
    AgentToolActivityKind.mcp => Icons.extension_rounded,
    AgentToolActivityKind.generic => Icons.build_rounded,
  };
}

class _CopyTranscriptButton extends StatelessWidget {
  const _CopyTranscriptButton({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final enabled = text.trim().isNotEmpty;
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    return Tooltip(
      message: AppTextLocalizer.text('复制', locale: locale),
      child: IconButton(
        key: kAgentToolDetailCopyButtonKey,
        constraints: const BoxConstraints.tightFor(width: 30, height: 30),
        padding: EdgeInsets.zero,
        visualDensity: VisualDensity.compact,
        icon: Icon(
          Icons.content_copy_rounded,
          size: 16,
          color: enabled
              ? palette.textSecondary
              : palette.textTertiary.withValues(alpha: 0.56),
        ),
        onPressed: enabled
            ? () async {
                await Clipboard.setData(ClipboardData(text: text));
                showToast(
                  AppTextLocalizer.text('已复制工具输出'),
                  type: ToastType.success,
                );
              }
            : null,
      ),
    );
  }
}

class _DetailTranscriptPanel extends StatelessWidget {
  const _DetailTranscriptPanel({
    required this.inputLabel,
    required this.resultLabel,
    required this.emptyResultLabel,
    required this.inputText,
    required this.resultText,
    required this.accentColor,
    required this.resultTextStyle,
  });

  final String inputLabel;
  final String resultLabel;
  final String emptyResultLabel;
  final String inputText;
  final String resultText;
  final Color accentColor;
  final AgentToolTranscriptTextStyle resultTextStyle;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final normalizedInput = inputText.trim();
    final normalizedResult = resultText.trimRight();
    final panelColor = context.isDarkTheme
        ? Color.alphaBlend(
            accentColor.withValues(alpha: 0.05),
            palette.surfaceSecondary,
          )
        : palette.previewFallback;
    final outputStyle = TextStyle(
      color: palette.textSecondary,
      fontSize: _kToolDetailFontSize,
      fontFamily: resultTextStyle == AgentToolTranscriptTextStyle.monospace
          ? 'monospace'
          : null,
      letterSpacing: 0,
      height: 1.42,
    );
    final inputStyle = outputStyle.copyWith(
      color: palette.textPrimary,
      fontFamily: 'monospace',
      fontWeight: FontWeight.w500,
      height: 1.35,
    );

    return DecoratedBox(
      key: kAgentToolDetailOutputPanelKey,
      decoration: BoxDecoration(
        color: panelColor,
        borderRadius: _kTranscriptSurfaceRadius,
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: Scrollbar(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(10, 10, 10, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _TranscriptSectionLabel(
                      label: inputLabel,
                      accentColor: accentColor,
                    ),
                    const SizedBox(height: 7),
                    SelectableText(
                      normalizedInput,
                      maxLines: 4,
                      style: inputStyle,
                    ),
                    const SizedBox(height: 12),
                    Divider(
                      height: 1,
                      thickness: 1,
                      color: palette.borderSubtle,
                    ),
                    const SizedBox(height: 10),
                    _TranscriptSectionLabel(
                      label: resultLabel,
                      accentColor: accentColor,
                    ),
                    const SizedBox(height: 7),
                    normalizedResult.isEmpty
                        ? Text(
                            emptyResultLabel,
                            style: outputStyle.copyWith(
                              color: palette.textTertiary,
                              fontFamily: null,
                              fontStyle: FontStyle.italic,
                            ),
                          )
                        : SelectableText.rich(
                            _buildOutputTextSpan(normalizedResult, outputStyle),
                          ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _TranscriptSectionLabel extends StatelessWidget {
  const _TranscriptSectionLabel({
    required this.label,
    required this.accentColor,
  });

  final String label;
  final Color accentColor;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Row(
      children: [
        Container(
          width: 6,
          height: 6,
          decoration: BoxDecoration(
            color: accentColor.withValues(alpha: 0.82),
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 7),
        Flexible(
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: palette.textTertiary,
              fontSize: _kToolDetailFontSize,
              fontWeight: FontWeight.w600,
              letterSpacing: 0,
              height: 1,
            ),
          ),
        ),
      ],
    );
  }
}

class _DetailMetaPill extends StatelessWidget {
  const _DetailMetaPill({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.11),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.08);
    return _DetailPill(
      label: label,
      foreground: Color.lerp(palette.textSecondary, color, 0.38)!,
      background: background,
      borderColor: color.withValues(alpha: 0.18),
    );
  }
}

class _DetailStatusPill extends StatelessWidget {
  const _DetailStatusPill({required this.status, required this.label});

  final String status;
  final String label;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = resolveAgentToolStatusColor(status);
    final background = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.12),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.10);
    return _DetailPill(
      label: label,
      foreground: Color.lerp(palette.textSecondary, color, 0.52)!,
      background: background,
      borderColor: color.withValues(alpha: 0.22),
    );
  }
}

class _DetailPill extends StatelessWidget {
  const _DetailPill({
    required this.label,
    required this.foreground,
    required this.background,
    required this.borderColor,
  });

  final String label;
  final Color foreground;
  final Color background;
  final Color borderColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(maxWidth: 148),
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: borderColor),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: foreground,
          fontSize: _kToolDetailFontSize,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
          height: 1,
        ),
      ),
    );
  }
}

String _buildDetailCopyText(AgentToolTranscript transcript) {
  return <String>[
    transcript.promptLine,
    if (transcript.outputText.trim().isNotEmpty)
      transcript.outputText.trimRight(),
  ].join('\n').trim();
}
