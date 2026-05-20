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
    this.initiallyExpanded = false,
    this.onLayoutChanged,
  });

  final AgentToolActivity activity;
  final bool compactSurface;
  final bool initiallyExpanded;
  final VoidCallback? onLayoutChanged;

  @override
  State<AgentToolActivityCard> createState() => _AgentToolActivityCardState();
}

class _AgentToolActivityCardState extends State<AgentToolActivityCard> {
  static const double _compactMaxWidthFactor = 0.76;
  static const double _compactMaxWidth = 360;
  static const double _compactMinWidth = 148;
  static const double _compactRadius = 8;
  static const double _compactAccentWidth = 3;
  static const double _processFontSize = 12;

  late bool _expanded = widget.initiallyExpanded;

  @override
  void didUpdateWidget(covariant AgentToolActivityCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.initiallyExpanded && widget.initiallyExpanded) {
      _expanded = true;
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final statusColor = resolveAgentToolStatusColor(widget.activity.status);
    final activityColor = AgentToolCardPolicy.activityColor(
      widget.activity.kind,
    );
    if (widget.compactSurface) {
      final compactBackgroundColor = context.isDarkTheme
          ? Color.alphaBlend(
              activityColor.withValues(alpha: 0.08),
              palette.surfacePrimary,
            )
          : activityColor.withValues(alpha: 0.055);
      final compactBorderColor = context.isDarkTheme
          ? Color.lerp(palette.borderSubtle, activityColor, 0.30)!
          : activityColor.withValues(alpha: 0.16);
      return Align(
        alignment: Alignment.centerLeft,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final maxWidth = (constraints.maxWidth * _compactMaxWidthFactor)
                .clamp(_compactMinWidth, _compactMaxWidth)
                .toDouble();
            return ConstrainedBox(
              key: ValueKey(
                'agent-tool-activity-compact-surface-${widget.activity.id}',
              ),
              constraints: BoxConstraints(
                minWidth: _compactMinWidth,
                maxWidth: maxWidth,
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Material(
                  color: Colors.transparent,
                  child: _buildSurface(
                    context,
                    borderRadius: BorderRadius.circular(_compactRadius),
                    decoration: BoxDecoration(
                      color: compactBackgroundColor,
                      borderRadius: BorderRadius.circular(_compactRadius),
                      border: Border.all(color: compactBorderColor),
                    ),
                    padding: EdgeInsets.zero,
                    showExpandedSteps: false,
                    accentColor: activityColor,
                  ),
                ),
              ),
            );
          },
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
    Color? accentColor,
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
        : DecoratedBox(
            decoration: decoration,
            child: accentColor == null
                ? content
                : ClipRRect(
                    borderRadius: borderRadius,
                    child: IntrinsicHeight(
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Container(
                            width: _compactAccentWidth,
                            color: accentColor,
                          ),
                          Flexible(
                            child: Padding(
                              padding: const EdgeInsets.fromLTRB(9, 7, 8, 7),
                              child: content,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
          );
    return InkWell(
      borderRadius: borderRadius,
      onTap: showExpandedSteps ? null : _handleSurfaceTap,
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

  void _handleSurfaceTap() {
    if (widget.compactSurface && widget.activity.stepCount == 1) {
      final step = widget.activity.steps.single;
      unawaited(
        _openActivityStepDetail(
          context,
          step: step,
          runLogId: _resolveActivityRunLogId(widget.activity),
          title: _stepTitle(context, step),
        ),
      );
      return;
    }
    _toggleExpanded();
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
    final activityColor = AgentToolCardPolicy.activityColor(activity.kind);
    final locale = Localizations.localeOf(context);
    final statusLabel = resolveAgentToolStatusLabel(<String, dynamic>{
      'status': activity.status,
      'toolType': AgentToolCardPolicy.toolTypeForKind(activity.kind),
    }, locale: locale);
    final label = _activityLabel(
      context,
      activity.kind,
      status: activity.status,
    );
    final title = _activityTitle(
      activity,
      expanded: expanded,
      compact: compact,
    );

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
              Row(
                children: [
                  Flexible(
                    child: Text(
                      compact
                          ? label
                          : '$label · ${activity.stepCount} ${_stepUnit(context, activity.stepCount)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: context.isDarkTheme
                            ? Color.lerp(
                                palette.textSecondary,
                                activityColor,
                                0.34,
                              )
                            : Color.lerp(Colors.black54, activityColor, 0.45),
                        fontSize: _AgentToolActivityCardState._processFontSize,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0,
                        height: 1.1,
                      ),
                    ),
                  ),
                  if (compact && activity.stepCount > 1) ...[
                    const SizedBox(width: 5),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 5,
                        vertical: 1.5,
                      ),
                      decoration: BoxDecoration(
                        color: activityColor.withValues(
                          alpha: context.isDarkTheme ? 0.16 : 0.10,
                        ),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        '${activity.stepCount}',
                        maxLines: 1,
                        style: TextStyle(
                          color: context.isDarkTheme
                              ? Color.lerp(
                                  palette.textSecondary,
                                  activityColor,
                                  0.42,
                                )
                              : activityColor,
                          fontSize:
                              _AgentToolActivityCardState._processFontSize,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0,
                          height: 1,
                        ),
                      ),
                    ),
                  ],
                ],
              ),
              if (title.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    compact
                        ? _localizedActivityText(context, title)
                        : '${_activityTitlePrefix(context)} · ${_localizedActivityText(context, title)}',
                    maxLines: compact ? 2 : 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: context.isDarkTheme
                          ? palette.textPrimary
                          : Colors.black87,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0,
                      height: 1.15,
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
                fontSize: _AgentToolActivityCardState._processFontSize,
                fontWeight: FontWeight.w600,
                letterSpacing: 0,
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
    final activityColor = AgentToolCardPolicy.activityColor(activity.kind);
    final toolType = AgentToolCardPolicy.toolTypeForKind(activity.kind);
    final iconColor = context.isDarkTheme
        ? Color.lerp(context.omniPalette.textSecondary, activityColor, 0.48)!
        : activityColor;
    final ringColor = activity.isRunning ? statusColor : activityColor;
    return Container(
      width: 18,
      height: 18,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: ringColor.withValues(alpha: context.isDarkTheme ? 0.18 : 0.13),
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
    final statusColor = resolveAgentToolStatusColor(step.status);
    final title = _stepTitle(context, step);
    final subtitle = _stepSubtitle(context, step);
    final preview = compact ? _stepPreview(context, step) : '';

    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () {
        unawaited(
          _openActivityStepDetail(
            context,
            step: step,
            runLogId: runLogId,
            title: title,
          ),
        );
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
                      fontSize: _AgentToolActivityCardState._processFontSize,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0,
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
                          fontSize:
                              _AgentToolActivityCardState._processFontSize,
                          fontWeight: FontWeight.w500,
                          letterSpacing: 0,
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
                          fontSize:
                              _AgentToolActivityCardState._processFontSize,
                          fontWeight: FontWeight.w500,
                          letterSpacing: 0,
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

String _activityLabel(
  BuildContext context,
  AgentToolActivityKind kind, {
  String status = '',
}) {
  final locale = Localizations.localeOf(context);
  switch (kind) {
    case AgentToolActivityKind.thinking:
      return AppTextLocalizer.choose(
        zh: status == 'running' ? '思考中' : '思考',
        en: status == 'running' ? 'Thinking' : 'Thought',
        locale: locale,
      );
    case AgentToolActivityKind.browser:
      return AppTextLocalizer.choose(
        zh: '浏览器操作',
        en: 'Browser activity',
        locale: locale,
      );
    case AgentToolActivityKind.research:
      return AppTextLocalizer.choose(
        zh: '网页搜索',
        en: 'Web search',
        locale: locale,
      );
    case AgentToolActivityKind.vlm:
      return AppTextLocalizer.choose(
        zh: '视觉执行',
        en: 'Visual task',
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
        en: 'Tool call',
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

String _localizedActivityText(BuildContext context, String value) {
  final locale = Localizations.localeOf(context);
  switch (value.trim()) {
    case 'Thinking':
      return AppTextLocalizer.choose(en: 'Thinking', zh: '思考', locale: locale);
    case 'Thought':
      return AppTextLocalizer.choose(en: 'Thought', zh: '思考', locale: locale);
    case 'Browser activity':
      return AppTextLocalizer.choose(
        en: 'Browser activity',
        zh: '浏览器操作',
        locale: locale,
      );
    case 'Research activity':
      return AppTextLocalizer.choose(
        en: 'Web search',
        zh: '网页搜索',
        locale: locale,
      );
    case 'Visual task':
    case 'Visual Task':
    case 'Device action':
      return AppTextLocalizer.choose(
        en: 'Visual task',
        zh: '视觉执行',
        locale: locale,
      );
    case '视觉执行':
    case '手机操作':
      return AppTextLocalizer.choose(
        en: 'Visual task',
        zh: '视觉执行',
        locale: locale,
      );
    case 'Terminal activity':
      return AppTextLocalizer.choose(
        en: 'Terminal activity',
        zh: '终端操作',
        locale: locale,
      );
    case 'Workspace activity':
      return AppTextLocalizer.choose(
        en: 'Workspace activity',
        zh: '工作区操作',
        locale: locale,
      );
    case 'Workbench activity':
      return AppTextLocalizer.choose(
        en: 'Workbench activity',
        zh: '工作台操作',
        locale: locale,
      );
    case 'MCP activity':
      return AppTextLocalizer.choose(
        en: 'MCP activity',
        zh: 'MCP 操作',
        locale: locale,
      );
    case 'Tool activity':
    case 'Tool call':
      return AppTextLocalizer.choose(
        en: 'Tool call',
        zh: '工具调用',
        locale: locale,
      );
  }
  return value;
}

String _stepTitle(BuildContext context, AgentToolActivityStep step) {
  final title = step.title.trim();
  if (title.isNotEmpty) {
    return _localizedActivityText(context, title);
  }
  final action = step.action.trim();
  if (action.isNotEmpty) {
    return action;
  }
  return AppTextLocalizer.choose(
    en: 'Tool call',
    zh: '工具调用',
    locale: Localizations.localeOf(context),
  );
}

String _stepSubtitle(BuildContext context, AgentToolActivityStep step) {
  final action = _localizedActionText(context, step.action).trim();
  final target = step.target.trim();
  if (action.isEmpty) {
    return target;
  }
  if (target.isEmpty || target == action) {
    return action;
  }
  return '$action · $target';
}

String _stepPreview(BuildContext context, AgentToolActivityStep step) {
  final cardData = step.message.cardData ?? const <String, dynamic>{};
  final preview = resolveAgentToolPreview(
    cardData,
    locale: Localizations.localeOf(context),
  ).trim();
  final title = _stepTitle(context, step).trim();
  final subtitle = _stepSubtitle(context, step).trim();
  if (preview.isEmpty ||
      preview == title ||
      preview == subtitle ||
      AgentToolCardPolicy.isPlaceholderText(preview)) {
    return '';
  }
  return preview;
}

String _localizedActionText(BuildContext context, String value) {
  final locale = Localizations.localeOf(context);
  switch (value.trim()) {
    case 'click':
      return AppTextLocalizer.choose(en: 'Tap', zh: '点击', locale: locale);
    case 'type':
      return AppTextLocalizer.choose(en: 'Type', zh: '输入', locale: locale);
    case 'scroll':
      return AppTextLocalizer.choose(en: 'Scroll', zh: '滚动', locale: locale);
    case 'long_press':
    case 'longPress':
      return AppTextLocalizer.choose(
        en: 'Long press',
        zh: '长按',
        locale: locale,
      );
    case 'open_app':
    case 'openApp':
      return AppTextLocalizer.choose(
        en: 'Open app',
        zh: '打开应用',
        locale: locale,
      );
    case 'press_home':
    case 'pressHome':
      return AppTextLocalizer.choose(en: 'Home', zh: '返回桌面', locale: locale);
    case 'press_back':
    case 'pressBack':
      return AppTextLocalizer.choose(en: 'Back', zh: '返回', locale: locale);
    case 'wait':
      return AppTextLocalizer.choose(en: 'Wait', zh: '等待', locale: locale);
    case 'record':
      return AppTextLocalizer.choose(en: 'Record', zh: '记录', locale: locale);
    case 'finished':
      return AppTextLocalizer.choose(en: 'Finish', zh: '完成', locale: locale);
    case 'info':
      return AppTextLocalizer.choose(
        en: 'Need input',
        zh: '请求协助',
        locale: locale,
      );
    case 'feedback':
      return AppTextLocalizer.choose(en: 'Feedback', zh: '反馈', locale: locale);
    case 'abort':
      return AppTextLocalizer.choose(en: 'Abort', zh: '中止', locale: locale);
    case 'hot_key':
    case 'hotKey':
      return AppTextLocalizer.choose(en: 'Shortcut', zh: '快捷键', locale: locale);
    case 'vlm_task':
    case 'mobile':
      return AppTextLocalizer.choose(
        en: 'Visual task',
        zh: '视觉执行',
        locale: locale,
      );
  }
  return value;
}

Future<void> _openActivityStepDetail(
  BuildContext context, {
  required AgentToolActivityStep step,
  required String runLogId,
  required String title,
}) {
  final cardData = step.message.cardData ?? const <String, dynamic>{};
  if (runLogId.isNotEmpty && step.cardId.isNotEmpty) {
    return showRunLogStepDetailSheet(
      context,
      runId: runLogId,
      cardId: step.cardId,
      title: title,
    );
  }
  return showAgentToolDetailSheet(context, cardData: cardData);
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
