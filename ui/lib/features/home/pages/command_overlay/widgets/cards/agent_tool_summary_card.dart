import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
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

  static const double _compactMaxWidthFactor = 0.84;
  static const double _compactMaxWidth = 360;
  static const double _compactMinWidth = 252;
  static const double _compactRadius = 10;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final status = (cardData['status'] ?? 'running').toString();
    final resolvedTitle = resolveAgentToolTitle(cardData, locale: locale);
    final summaryTitle = resolveAgentToolSummaryText(cardData, locale: locale);
    final activityKind = AgentToolCardPolicy.activityKindFor(cardData);
    final title =
        _summaryCardPrefersSummary(activityKind) && summaryTitle.isNotEmpty
        ? summaryTitle
        : resolvedTitle;
    final statusLabel = resolveAgentToolStatusLabel(cardData, locale: locale);
    final preview = resolveAgentToolPreview(cardData, locale: locale);
    final statusColor = resolveAgentToolStatusColor(status);
    final runLogRef = AgentToolCardPolicy.runLogRef(cardData);
    final isVlmTaskWrapper =
        activityKind == AgentToolActivityKind.vlm &&
        (cardData['toolName'] ?? '').toString().trim() == 'vlm_task';
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
    final availableCardWidth =
        MediaQuery.of(context).size.width * _compactMaxWidthFactor;
    final cardWidth = availableCardWidth < _compactMinWidth
        ? availableCardWidth
        : availableCardWidth
              .clamp(_compactMinWidth, _compactMaxWidth)
              .toDouble();

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
            minWidth: cardWidth,
            maxWidth: cardWidth,
            minHeight: 38,
          ),
          child: Container(
            margin: const EdgeInsets.only(top: 6, bottom: 2),
            child: Material(
              color: Colors.transparent,
              borderRadius: BorderRadius.circular(_compactRadius),
              clipBehavior: Clip.antiAlias,
              child: Ink(
                decoration: BoxDecoration(
                  color: cardBackgroundColor,
                  borderRadius: BorderRadius.circular(_compactRadius),
                  border: Border.all(color: cardBorderColor),
                ),
                child: InkWell(
                  onTap: () {
                    if (isVlmTaskWrapper && runLogRef.hasRunLog) {
                      unawaited(
                        showRunLogTimelineSheet(
                          context,
                          runId: runLogRef.runLogId,
                          title: resolveAgentToolTitle(
                            cardData,
                            locale: locale,
                          ),
                        ),
                      );
                    } else if (runLogRef.hasStep) {
                      unawaited(
                        showRunLogStepDetailSheet(
                          context,
                          runId: runLogRef.runLogId,
                          cardId: runLogRef.cardId,
                          title: resolveAgentToolTitle(
                            cardData,
                            locale: locale,
                          ),
                        ),
                      );
                    } else if (runLogRef.hasRunLog) {
                      unawaited(
                        showRunLogTimelineSheet(
                          context,
                          runId: runLogRef.runLogId,
                          title: resolveAgentToolTitle(
                            cardData,
                            locale: locale,
                          ),
                        ),
                      );
                    } else {
                      unawaited(
                        showAgentToolDetailSheet(context, cardData: cardData),
                      );
                    }
                  },
                  borderRadius: BorderRadius.circular(_compactRadius),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 9, 10, 9),
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
                              letterSpacing: 0,
                              height: 1.15,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        _SummaryTag(
                          label: statusLabel,
                          backgroundColor: statusTagBackgroundColor,
                          textColor: statusTagTextColor,
                        ),
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

bool _summaryCardPrefersSummary(AgentToolActivityKind? kind) {
  return kind == AgentToolActivityKind.vlm ||
      kind == AgentToolActivityKind.research ||
      kind == AgentToolActivityKind.generic ||
      kind == AgentToolActivityKind.workbench;
}

class _SummaryTag extends StatelessWidget {
  const _SummaryTag({
    required this.label,
    required this.backgroundColor,
    required this.textColor,
  });

  final String label;
  final Color backgroundColor;
  final Color textColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: textColor,
          fontSize: 12,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
          height: 1,
        ),
      ),
    );
  }
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
