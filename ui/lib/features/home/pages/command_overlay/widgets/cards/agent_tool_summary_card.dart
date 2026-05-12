import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/theme_context.dart';

class AgentToolSummaryCard extends StatelessWidget {
  const AgentToolSummaryCard({
    super.key,
    required this.cardData,
    this.parentScrollController,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
  });

  final Map<String, dynamic> cardData;
  final ScrollController? parentScrollController;
  final AppBackgroundVisualProfile visualProfile;

  @override
  Widget build(BuildContext context) {
    final status = (cardData['status'] ?? 'running').toString();
    final title = resolveAgentToolTitle(cardData);
    final statusLabel = resolveAgentToolStatusLabel(cardData);
    final preview = resolveAgentToolPreview(cardData);
    final typeLabel = resolveAgentToolTypeLabel(cardData);
    final statusColor = resolveAgentToolStatusColor(status);
    final runLogId = _resolveRunLogId(cardData);
    final palette = context.omniPalette;
    final cardBackgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            statusColor.withValues(alpha: status == 'running' ? 0.11 : 0.09),
            palette.surfaceSecondary,
          )
        : statusColor.withValues(alpha: 0.08);
    final cardBorderColor = context.isDarkTheme
        ? Color.lerp(
            palette.borderSubtle,
            statusColor,
            0.18,
          )!.withValues(alpha: 0.92)
        : Colors.transparent;
    final statusTagBackgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            statusColor.withValues(alpha: 0.14),
            palette.surfaceElevated,
          )
        : Colors.white.withValues(alpha: 0.78);
    final statusTagTextColor = context.isDarkTheme
        ? Color.lerp(palette.textSecondary, statusColor, 0.38)!
        : statusColor;
    final titleColor = context.isDarkTheme
        ? palette.textPrimary
        : visualProfile.primaryTextColor;

    final tooltipLines = <String>[title];
    if (preview.isNotEmpty && preview != title) {
      tooltipLines.add(preview);
    }

    return Tooltip(
      message: tooltipLines.join('\n'),
      child: Align(
        alignment: Alignment.centerLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.78,
            minHeight: 34,
          ),
          child: Container(
            margin: const EdgeInsets.only(top: 6, bottom: 2),
            child: Material(
              color: Colors.transparent,
              borderRadius: BorderRadius.circular(999),
              clipBehavior: Clip.antiAlias,
              child: Ink(
                decoration: BoxDecoration(
                  color: cardBackgroundColor,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: cardBorderColor),
                ),
                child: InkWell(
                  onTap: () => unawaited(
                    showAgentToolDetailSheet(context, cardData: cardData),
                  ),
                  borderRadius: BorderRadius.circular(999),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 10, 8),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _StatusIcon(
                          status: status,
                          toolType: cardData['toolType'],
                        ),
                        const SizedBox(width: 8),
                        Flexible(
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: titleColor,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                              height: 1.15,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 7,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: statusTagBackgroundColor,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(
                            status == 'running' ? typeLabel : statusLabel,
                            style: TextStyle(
                              color: statusTagTextColor,
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                              height: 1,
                            ),
                          ),
                        ),
                        if (runLogId.isNotEmpty) ...[
                          const SizedBox(width: 6),
                          Tooltip(
                            message: _text(context, '查看 RunLog', 'View RunLog'),
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: () => _openRunLog(runLogId),
                              child: Icon(
                                Icons.route_rounded,
                                size: 15,
                                color: statusTagTextColor,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

void _openRunLog(String runLogId) {
  GoRouterManager.push(
    '/task/run_log_timeline',
    extra: {'runId': runLogId, 'title': 'RunLog'},
  );
}

String _resolveRunLogId(Map<String, dynamic> cardData) {
  return _firstNonBlank([
    cardData['runLogId'],
    cardData['run_log_id'],
    cardData['runId'],
    cardData['run_id'],
    _runLogIdFromJsonString(cardData['resultPreviewJson']),
    _runLogIdFromJsonString(cardData['rawResultJson']),
    cardData['toolTaskId'],
    cardData['taskId'],
    cardData['taskID'],
  ]);
}

String _runLogIdFromJsonString(dynamic raw) {
  final text = raw?.toString().trim() ?? '';
  if (text.isEmpty) return '';
  try {
    final decoded = jsonDecode(text);
    if (decoded is! Map) return '';
    return _firstNonBlank([
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

String _firstNonBlank(Iterable<Object?> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
}

String _text(BuildContext context, String zh, String en) {
  return Localizations.localeOf(context).languageCode == 'zh' ? zh : en;
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status, required this.toolType});

  final String status;
  final dynamic toolType;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    final backgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.14),
            context.omniPalette.surfaceElevated,
          )
        : color.withValues(alpha: 0.12);
    final iconColor = context.isDarkTheme
        ? Color.lerp(context.omniPalette.textSecondary, color, 0.38)!
        : color;
    return Container(
      width: 18,
      height: 18,
      decoration: BoxDecoration(color: backgroundColor, shape: BoxShape.circle),
      child: Center(
        child: status == 'running'
            ? SizedBox(
                width: 8,
                height: 8,
                child: CircularProgressIndicator(
                  strokeWidth: 1.4,
                  valueColor: AlwaysStoppedAnimation<Color>(iconColor),
                ),
              )
            : Icon(
                resolveAgentToolStatusIcon(status, (toolType ?? '').toString()),
                size: 10,
                color: iconColor,
              ),
      ),
    );
  }
}
