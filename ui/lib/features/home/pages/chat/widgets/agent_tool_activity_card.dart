import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/utils/agent_activity_compactor.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/theme/theme_context.dart';

class AgentToolActivityCard extends StatefulWidget {
  const AgentToolActivityCard({
    super.key,
    required this.activity,
    this.compactSurface = false,
    this.onLayoutChanged,
  });

  final AgentToolActivity activity;
  final bool compactSurface;
  final VoidCallback? onLayoutChanged;

  @override
  State<AgentToolActivityCard> createState() => _AgentToolActivityCardState();
}

class _AgentToolActivityCardState extends State<AgentToolActivityCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final statusColor = resolveAgentToolStatusColor(widget.activity.status);
    if (widget.compactSurface) {
      return Padding(
        padding: const EdgeInsets.only(top: 2, bottom: 2),
        child: Material(
          color: Colors.transparent,
          child: _buildSurface(
            context,
            borderRadius: BorderRadius.circular(8),
            decoration: BoxDecoration(
              color: Colors.transparent,
              borderRadius: BorderRadius.circular(8),
            ),
            padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
            showExpandedSteps: false,
          ),
        ),
      );
    }
    final backgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            statusColor.withValues(alpha: 0.08),
            palette.surfaceSecondary,
          )
        : statusColor.withValues(alpha: 0.06);
    final borderColor = context.isDarkTheme
        ? Color.lerp(palette.borderSubtle, statusColor, 0.16)!
        : statusColor.withValues(alpha: 0.16);

    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.82,
        ),
        child: Padding(
          padding: const EdgeInsets.only(top: 6, bottom: 2),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: backgroundColor,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: borderColor),
            ),
            child: Material(
              color: Colors.transparent,
              child: _buildSurface(
                context,
                borderRadius: BorderRadius.circular(12),
                padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
                showExpandedSteps: false,
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSurface(
    BuildContext context, {
    required BorderRadius borderRadius,
    required EdgeInsets padding,
    BoxDecoration? decoration,
    required bool showExpandedSteps,
  }) {
    final content = Padding(
      padding: padding,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ActivityHeader(
            activity: widget.activity,
            expanded: showExpandedSteps || _expanded,
            compact: widget.compactSurface,
          ),
          _buildDetailSection(showExpandedSteps: showExpandedSteps),
        ],
      ),
    );

    final child = decoration == null
        ? content
        : DecoratedBox(decoration: decoration, child: content);
    return InkWell(
      borderRadius: borderRadius,
      onTap: showExpandedSteps ? null : _toggleExpanded,
      child: child,
    );
  }

  Widget _buildDetailSection({required bool showExpandedSteps}) {
    if (showExpandedSteps) {
      return _ActivityStepList(activity: widget.activity, compact: true);
    }
    if (widget.compactSurface) {
      return _expanded
          ? _ActivityStepList(activity: widget.activity, compact: true)
          : const SizedBox.shrink();
    }
    return AnimatedCrossFade(
      firstChild: const SizedBox.shrink(),
      secondChild: _ActivityStepList(activity: widget.activity),
      crossFadeState: _expanded
          ? CrossFadeState.showSecond
          : CrossFadeState.showFirst,
      duration: const Duration(milliseconds: 180),
      sizeCurve: Curves.easeOutCubic,
      firstCurve: Curves.easeOutCubic,
      secondCurve: Curves.easeOutCubic,
    );
  }

  void _toggleExpanded() {
    setState(() {
      _expanded = !_expanded;
    });
    widget.onLayoutChanged?.call();
  }
}

class _ActivityHeader extends StatelessWidget {
  const _ActivityHeader({
    required this.activity,
    required this.expanded,
    this.compact = false,
  });

  final AgentToolActivity activity;
  final bool expanded;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final statusColor = resolveAgentToolStatusColor(activity.status);
    final statusLabel = resolveAgentToolStatusLabel(<String, dynamic>{
      'status': activity.status,
      'toolType': AgentToolCardPolicy.toolTypeForKind(activity.kind),
    });
    final label = _activityLabel(context, activity.kind);
    final title = _activityTitle(
      activity,
      expanded: expanded,
      compact: compact,
    );

    final preview = _activityHeaderPreview(context, activity);

    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _ActivityStatusIcon(activity: activity),
        const SizedBox(width: 8),
        Flexible(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '$label · ${activity.stepCount} ${_stepUnit(context, activity.stepCount)}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: context.isDarkTheme
                      ? palette.textSecondary
                      : Colors.black54,
                  fontSize: 10,
                  fontWeight: FontWeight.w600,
                  height: 1.1,
                ),
              ),
              if (title.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    compact
                        ? title
                        : '${_activityTitlePrefix(context)} · $title',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: context.isDarkTheme
                          ? palette.textPrimary
                          : Colors.black87,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      height: 1.15,
                    ),
                  ),
                ),
              if (compact && preview.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 3),
                  child: Text(
                    preview,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 10,
                      fontWeight: FontWeight.w500,
                      height: 1.2,
                    ),
                  ),
                ),
            ],
          ),
        ),
        if (!compact) ...[
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
            decoration: BoxDecoration(
              color: context.isDarkTheme
                  ? Color.alphaBlend(
                      statusColor.withValues(alpha: 0.14),
                      palette.surfaceElevated,
                    )
                  : Colors.white.withValues(alpha: 0.8),
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              statusLabel,
              style: TextStyle(
                color: context.isDarkTheme
                    ? Color.lerp(palette.textSecondary, statusColor, 0.38)
                    : statusColor,
                fontSize: 10,
                fontWeight: FontWeight.w600,
                height: 1,
              ),
            ),
          ),
          const SizedBox(width: 6),
          AnimatedRotation(
            turns: expanded ? 0 : -0.25,
            duration: const Duration(milliseconds: 180),
            child: Icon(
              Icons.keyboard_arrow_down_rounded,
              size: 16,
              color: palette.textSecondary,
            ),
          ),
        ],
      ],
    );
  }
}

class _ActivityStatusIcon extends StatelessWidget {
  const _ActivityStatusIcon({required this.activity});

  final AgentToolActivity activity;

  @override
  Widget build(BuildContext context) {
    final statusColor = resolveAgentToolStatusColor(activity.status);
    final toolType = AgentToolCardPolicy.toolTypeForKind(activity.kind);
    final iconColor = context.isDarkTheme
        ? Color.lerp(context.omniPalette.textSecondary, statusColor, 0.38)!
        : statusColor;
    return Container(
      width: 18,
      height: 18,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: statusColor.withValues(alpha: context.isDarkTheme ? 0.14 : 0.12),
      ),
      child: Center(
        child: activity.isRunning
            ? SizedBox(
                width: 8,
                height: 8,
                child: CircularProgressIndicator(
                  strokeWidth: 1.4,
                  valueColor: AlwaysStoppedAnimation<Color>(iconColor),
                ),
              )
            : Icon(
                resolveAgentToolStatusIcon(activity.status, toolType),
                size: 10,
                color: iconColor,
              ),
      ),
    );
  }
}

class _ActivityStepList extends StatelessWidget {
  const _ActivityStepList({required this.activity, this.compact = false});

  final AgentToolActivity activity;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(top: compact ? 6 : 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var index = 0; index < activity.steps.length; index++)
            _ActivityStepRow(
              step: activity.steps[index],
              runLogId: _resolveActivityRunLogId(activity),
              showConnector: index < activity.steps.length - 1,
              compact: compact,
            ),
        ],
      ),
    );
  }
}

class _ActivityStepRow extends StatelessWidget {
  const _ActivityStepRow({
    required this.step,
    required this.runLogId,
    required this.showConnector,
    this.compact = false,
  });

  final AgentToolActivityStep step;
  final String runLogId;
  final bool showConnector;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final cardData = step.message.cardData ?? const <String, dynamic>{};
    final statusColor = resolveAgentToolStatusColor(step.status);
    final title = _stepTitle(step);
    final subtitle = _stepSubtitle(step);
    final preview = compact ? _stepPreview(step) : '';

    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () {
        if (runLogId.isNotEmpty && step.cardId.isNotEmpty) {
          unawaited(
            showRunLogStepDetailSheet(
              context,
              runId: runLogId,
              cardId: step.cardId,
              title: title,
            ),
          );
        } else {
          unawaited(showAgentToolDetailSheet(context, cardData: cardData));
        }
      },
      child: Padding(
        padding: EdgeInsets.symmetric(vertical: compact ? 2 : 3),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Column(
              children: [
                Container(
                  width: 7,
                  height: 7,
                  margin: const EdgeInsets.only(top: 5),
                  decoration: BoxDecoration(
                    color: step.isRetry
                        ? const Color(0xFFE49B20)
                        : statusColor.withValues(alpha: 0.86),
                    shape: BoxShape.circle,
                  ),
                ),
                if (showConnector)
                  Container(
                    width: 1,
                    height: 22,
                    margin: const EdgeInsets.only(top: 2),
                    color: palette.borderSubtle.withValues(alpha: 0.65),
                  ),
              ],
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    step.isRetry
                        ? '${AppTextLocalizer.choose(zh: '重试', en: 'Retry', locale: Localizations.localeOf(context))} · $title'
                        : title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      height: 1.2,
                    ),
                  ),
                  if (subtitle.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 10,
                          fontWeight: FontWeight.w500,
                          height: 1.2,
                        ),
                      ),
                    ),
                  if (preview.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        preview,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textTertiary,
                          fontSize: 10,
                          fontWeight: FontWeight.w500,
                          height: 1.2,
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String _activityLabel(BuildContext context, AgentToolActivityKind kind) {
  final locale = Localizations.localeOf(context);
  switch (kind) {
    case AgentToolActivityKind.browser:
      return AppTextLocalizer.choose(
        zh: '浏览器操作',
        en: 'Browser activity',
        locale: locale,
      );
    case AgentToolActivityKind.research:
      return AppTextLocalizer.choose(
        zh: '网页搜索',
        en: 'Research activity',
        locale: locale,
      );
    case AgentToolActivityKind.terminal:
      return AppTextLocalizer.choose(
        zh: '终端操作',
        en: 'Terminal activity',
        locale: locale,
      );
    case AgentToolActivityKind.workspace:
      return AppTextLocalizer.choose(
        zh: '工作区操作',
        en: 'Workspace activity',
        locale: locale,
      );
    case AgentToolActivityKind.workbench:
      return AppTextLocalizer.choose(
        zh: '工作台操作',
        en: 'Workbench activity',
        locale: locale,
      );
    case AgentToolActivityKind.mcp:
      return AppTextLocalizer.choose(
        zh: 'MCP 操作',
        en: 'MCP activity',
        locale: locale,
      );
    case AgentToolActivityKind.generic:
      return AppTextLocalizer.choose(
        zh: '工具调用',
        en: 'Tool activity',
        locale: locale,
      );
  }
}

String _stepUnit(BuildContext context, int count) {
  return AppTextLocalizer.choose(
    zh: '步',
    en: count == 1 ? 'step' : 'steps',
    locale: Localizations.localeOf(context),
  );
}

String _activityTitlePrefix(BuildContext context) {
  return AppTextLocalizer.choose(
    zh: '目标',
    en: 'Target',
    locale: Localizations.localeOf(context),
  );
}

String _activityTitle(
  AgentToolActivity activity, {
  required bool expanded,
  required bool compact,
}) {
  if (expanded && (compact || activity.stepCount > 1)) {
    return '';
  }
  final title = activity.title.trim();
  if (title.isEmpty ||
      title == AgentToolCardPolicy.activityKindLabel(activity.kind)) {
    return '';
  }
  return title;
}

String _activityHeaderPreview(
  BuildContext context,
  AgentToolActivity activity,
) {
  if (activity.steps.length <= 1) {
    return '';
  }
  final latest = _stepPreview(activity.steps.last);
  if (latest.isNotEmpty) {
    return latest;
  }
  final locale = Localizations.localeOf(context);
  return AppTextLocalizer.choose(
    zh: '展开查看每一步',
    en: 'Expand to inspect steps',
    locale: locale,
  );
}

String _stepTitle(AgentToolActivityStep step) {
  final title = step.title.trim();
  if (title.isNotEmpty) {
    return title;
  }
  final action = step.action.trim();
  if (action.isNotEmpty) {
    return action;
  }
  return AppTextLocalizer.text('工具调用');
}

String _stepSubtitle(AgentToolActivityStep step) {
  final action = step.action.trim();
  final target = step.target.trim();
  if (action.isEmpty) {
    return target;
  }
  if (target.isEmpty || target == action) {
    return action;
  }
  return '$action · $target';
}

String _stepPreview(AgentToolActivityStep step) {
  final cardData = step.message.cardData ?? const <String, dynamic>{};
  final preview = resolveAgentToolPreview(cardData).trim();
  final title = _stepTitle(step).trim();
  final subtitle = _stepSubtitle(step).trim();
  if (preview.isEmpty ||
      preview == title ||
      preview == subtitle ||
      AgentToolCardPolicy.isPlaceholderText(preview)) {
    return '';
  }
  return preview;
}

String _resolveActivityRunLogId(AgentToolActivity activity) {
  for (final message in activity.messages) {
    final runLogId = AgentToolCardPolicy.runLogRef(
      message.cardData,
      message: message,
    ).runLogId;
    if (runLogId.isNotEmpty) {
      return runLogId;
    }
  }
  return activity.taskId;
}
