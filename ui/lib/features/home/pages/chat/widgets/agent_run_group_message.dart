import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/utils/agent_activity_compactor.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_tool_activity_card.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/card_widget_factory.dart'
    show OnBeforeTaskExecute, OnRequestAuthorize;
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_avatar.dart';

class AgentRunGroupMessage extends StatefulWidget {
  const AgentRunGroupMessage({
    super.key,
    required this.group,
    required this.expanded,
    required this.onToggleExpanded,
    required this.onBeforeTaskExecute,
    this.onCancelTask,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onStreamingTextLayoutChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
  });

  final AgentRunTimelineGroup group;
  final bool expanded;
  final VoidCallback onToggleExpanded;
  final OnBeforeTaskExecute onBeforeTaskExecute;
  final void Function(String taskId)? onCancelTask;
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final OnRequestAuthorize? onRequestAuthorize;
  final VoidCallback? onStreamingTextLayoutChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  @override
  State<AgentRunGroupMessage> createState() => _AgentRunGroupMessageState();
}

class _AgentRunGroupMessageState extends State<AgentRunGroupMessage>
    with SingleTickerProviderStateMixin {
  static const Duration _kToggleDuration = Duration(milliseconds: 260);

  late final AnimationController _expandController;
  late final Animation<double> _sizeFactor;
  late final Animation<double> _opacity;
  late final Animation<double> _lift;
  bool _isNotifyingParentDuringAnimation = false;
  final _compactor = AgentActivityCompactor();

  @override
  void initState() {
    super.initState();
    _expandController = AnimationController(
      vsync: this,
      duration: _kToggleDuration,
      reverseDuration: _kToggleDuration,
      value: widget.expanded ? 1.0 : 0.0,
    );
    _sizeFactor = CurvedAnimation(
      parent: _expandController,
      curve: Curves.easeInOutCubicEmphasized,
    );
    _opacity = CurvedAnimation(
      parent: _expandController,
      curve: const Interval(0.12, 1.0, curve: Curves.easeOutCubic),
      reverseCurve: const Interval(0.0, 0.72, curve: Curves.easeOutCubic),
    );
    _lift = Tween<double>(begin: -6, end: 0).animate(
      CurvedAnimation(
        parent: _expandController,
        curve: Curves.easeOutCubic,
        reverseCurve: Curves.easeInCubic,
      ),
    );
    _expandController.addListener(_handleAnimationTick);
    _expandController.addStatusListener(_handleAnimationStatusChanged);
    AgentAvatarService.ensureLoaded();
  }

  @override
  void didUpdateWidget(covariant AgentRunGroupMessage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.expanded == oldWidget.expanded) {
      return;
    }
    _isNotifyingParentDuringAnimation = true;
    if (widget.expanded) {
      _expandController.forward();
    } else {
      _expandController.reverse();
    }
  }

  @override
  void dispose() {
    _expandController
      ..removeListener(_handleAnimationTick)
      ..removeStatusListener(_handleAnimationStatusChanged)
      ..dispose();
    super.dispose();
  }

  void _handleAnimationTick() {
    if (!mounted || !_isNotifyingParentDuringAnimation) {
      return;
    }
    widget.onStreamingTextLayoutChanged?.call();
  }

  void _handleAnimationStatusChanged(AnimationStatus status) {
    if (status != AnimationStatus.completed &&
        status != AnimationStatus.dismissed) {
      return;
    }
    final shouldNotifyParent = _isNotifyingParentDuringAnimation;
    _isNotifyingParentDuringAnimation = false;
    if (!mounted) {
      return;
    }
    setState(() {});
    if (shouldNotifyParent) {
      widget.onStreamingTextLayoutChanged?.call();
    }
  }

  @override
  Widget build(BuildContext context) {
    final processMessages = widget.group.processMessagesOldestFirst;
    final visibleMessages = widget.group.visibleMessagesOldestFirst;
    final hasProcessMessages = processMessages.isNotEmpty;
    final isRunLogOnly = widget.group.isRunLogOnly;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _AgentRunSummaryHeader(
          key: ValueKey('agent-run-summary-${widget.group.taskId}'),
          taskId: widget.group.taskId,
          runLogId: widget.group.runLogId,
          isActiveRun: widget.group.isActiveRun,
          expanded: widget.expanded,
          thinkingCount: widget.group.thinkingCount,
          toolCount: widget.group.toolCount,
          latestProcessSummary: _latestProcessSummary(processMessages),
          onTap: hasProcessMessages
              ? widget.onToggleExpanded
              : isRunLogOnly
              ? () => _openRunLog(context)
              : null,
          showToggle: hasProcessMessages,
          showRunLogButton: isRunLogOnly,
        ),
        _buildAnimatedProcessSection(processMessages),
        ...visibleMessages.map(
          (message) => MessageBubble(
            key: ValueKey('agent-run-${widget.group.taskId}-${message.id}'),
            message: message,
            onBeforeTaskExecute: widget.onBeforeTaskExecute,
            onCancelTask: widget.onCancelTask,
            enableThinkingCollapse: false,
            parentScrollController: widget.parentScrollController,
            onParentScrollHandoff: widget.onParentScrollHandoff,
            onRequestAuthorize: widget.onRequestAuthorize,
            onStreamingTextLayoutChanged: widget.onStreamingTextLayoutChanged,
            visualProfile: widget.visualProfile,
            appearanceConfig: widget.appearanceConfig,
          ),
        ),
      ],
    );
  }

  void _openRunLog(BuildContext context) {
    final runLogId = widget.group.runLogId.trim().isEmpty
        ? widget.group.taskId
        : widget.group.runLogId.trim();
    showRunLogTimelineSheet(context, runId: runLogId, title: 'RunLog');
  }

  Widget _buildAnimatedProcessSection(List<ChatMessageModel> processMessages) {
    if (processMessages.isEmpty) {
      return const SizedBox.shrink();
    }

    final shouldShow =
        widget.expanded ||
        _expandController.isAnimating ||
        _expandController.value > 0.001;
    if (!shouldShow) {
      return const SizedBox.shrink();
    }

    final processItems = _compactor.compact(processMessages);

    return AnimatedBuilder(
      animation: _expandController,
      child: Column(
        key: ValueKey('agent-run-process-${widget.group.taskId}'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: processItems
            .map((item) {
              final activity = item.activity;
              if (activity != null) {
                return AgentToolActivityCard(
                  key: ValueKey(
                    'agent-run-${widget.group.taskId}-${activity.id}-${widget.expanded}',
                  ),
                  activity: activity,
                  compactSurface: true,
                  onLayoutChanged: widget.onStreamingTextLayoutChanged,
                );
              }
              final message = item.message!;
              if (_isThinkingProcessMessage(message)) {
                return _AgentThinkingActivityRow(
                  key: ValueKey(
                    'agent-run-${widget.group.taskId}-${message.id}-${widget.expanded}',
                  ),
                  message: message,
                  onLayoutChanged: widget.onStreamingTextLayoutChanged,
                );
              }
              return MessageBubble(
                key: ValueKey(
                  'agent-run-${widget.group.taskId}-${message.id}-${widget.expanded}',
                ),
                message: message,
                onBeforeTaskExecute: widget.onBeforeTaskExecute,
                onCancelTask: widget.onCancelTask,
                enableThinkingCollapse: true,
                thinkingAutoCollapseOnComplete: true,
                parentScrollController: widget.parentScrollController,
                onParentScrollHandoff: widget.onParentScrollHandoff,
                onRequestAuthorize: widget.onRequestAuthorize,
                onStreamingTextLayoutChanged:
                    widget.onStreamingTextLayoutChanged,
                visualProfile: widget.visualProfile,
                appearanceConfig: widget.appearanceConfig,
              );
            })
            .toList(growable: false)
            .cast<Widget>(),
      ),
      builder: (context, child) {
        final sizeFactor = _sizeFactor.value.clamp(0.0, 1.0);
        final opacity = _opacity.value.clamp(0.0, 1.0);
        return Padding(
          padding: const EdgeInsets.only(top: 2, bottom: 6),
          child: ClipRect(
            child: Align(
              alignment: Alignment.topCenter,
              heightFactor: sizeFactor,
              child: Transform.translate(
                offset: Offset(0, _lift.value),
                child: IgnorePointer(
                  ignoring: !widget.expanded && !_expandController.isAnimating,
                  child: Opacity(opacity: opacity, child: child),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  String _latestProcessSummary(List<ChatMessageModel> processMessages) {
    for (final message in processMessages.reversed) {
      final cardData = message.cardData;
      final cardType = (cardData?['type'] ?? '').toString();
      if (cardType == kAgentToolSummaryCardType && cardData != null) {
        final title = resolveAgentToolTitle(cardData).trim();
        if (title.isNotEmpty) {
          return title;
        }
      }
      if (cardType == 'deep_thinking') {
        final text = (cardData?['thinkingContent'] ?? '').toString().trim();
        if (text.isNotEmpty) {
          final firstLine = text
              .split('\n')
              .map((line) => line.trim())
              .firstWhere((line) => line.isNotEmpty, orElse: () => '');
          if (firstLine.isNotEmpty) {
            return firstLine;
          }
        }
      }
    }
    return '';
  }
}

class _AgentRunSummaryHeader extends StatelessWidget {
  const _AgentRunSummaryHeader({
    super.key,
    required this.taskId,
    required this.runLogId,
    required this.isActiveRun,
    required this.expanded,
    required this.thinkingCount,
    required this.toolCount,
    required this.latestProcessSummary,
    required this.onTap,
    required this.showToggle,
    required this.showRunLogButton,
  });

  final String taskId;
  final String runLogId;
  final bool isActiveRun;
  final bool expanded;
  final int thinkingCount;
  final int toolCount;
  final String latestProcessSummary;
  final VoidCallback? onTap;
  final bool showToggle;
  final bool showRunLogButton;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final isRunLogOnly = !isActiveRun && !showToggle;
    final label = AppTextLocalizer.choose(
      zh: isRunLogOnly ? '运行记录' : '步骤',
      en: isRunLogOnly ? 'RunLog' : 'Steps',
      locale: locale,
    );
    final statusLabel = isRunLogOnly
        ? AppTextLocalizer.choose(zh: '已记录', en: 'Logged', locale: locale)
        : isActiveRun
        ? AppTextLocalizer.choose(zh: '进行中', en: 'Running', locale: locale)
        : AppTextLocalizer.choose(zh: '已完成', en: 'Done', locale: locale);
    final summary = _summaryText(locale);
    final labelColor = expanded ? palette.textSecondary : palette.textTertiary;
    final lineColor = expanded
        ? palette.textSecondary.withValues(
            alpha: context.isDarkTheme ? 0.32 : 0.28,
          )
        : palette.borderSubtle.withValues(
            alpha: context.isDarkTheme ? 0.56 : 0.8,
          );

    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ValueListenableBuilder<AgentAvatarState>(
                  valueListenable: AgentAvatarService.avatarStateNotifier,
                  builder: (context, state, _) {
                    return AgentAvatarCircle(
                      key: ValueKey('agent-run-avatar-$taskId'),
                      state: state,
                      size: 30,
                    );
                  },
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final showSummary =
                          summary.isNotEmpty && constraints.maxWidth >= 142;
                      final showDivider = constraints.maxWidth >= 188;
                      return Row(
                        children: [
                          Flexible(
                            child: Text(
                              label,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                                color: labelColor,
                                fontFamily: 'PingFang SC',
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          _ProcessStatusPill(
                            label: statusLabel,
                            isActiveRun: isActiveRun,
                            expanded: expanded,
                          ),
                          if (showSummary) ...[
                            const SizedBox(width: 8),
                            Flexible(
                              child: Text(
                                summary,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w500,
                                  color: palette.textTertiary,
                                  height: 1.1,
                                ),
                              ),
                            ),
                          ],
                          if (showDivider) ...[
                            const SizedBox(width: 10),
                            SizedBox(
                              width: 42,
                              child: Container(height: 1, color: lineColor),
                            ),
                          ],
                        ],
                      );
                    },
                  ),
                ),
                if (showRunLogButton) ...[
                  const SizedBox(width: 6),
                  Tooltip(
                    message: AppTextLocalizer.choose(
                      zh: '查看 RunLog',
                      en: 'View RunLog',
                      locale: locale,
                    ),
                    child: InkResponse(
                      onTap: () {
                        final resolvedRunLogId = runLogId.trim().isEmpty
                            ? taskId
                            : runLogId.trim();
                        showRunLogTimelineSheet(
                          context,
                          runId: resolvedRunLogId,
                          title: 'RunLog',
                        );
                      },
                      radius: 18,
                      child: Icon(
                        Icons.route_rounded,
                        size: 16,
                        color: labelColor,
                      ),
                    ),
                  ),
                ],
                if (showToggle) ...[
                  const SizedBox(width: 8),
                  AnimatedRotation(
                    turns: expanded ? 0 : -0.25,
                    duration: _AgentRunGroupMessageState._kToggleDuration,
                    curve: Curves.easeInOutCubicEmphasized,
                    child: Icon(
                      Icons.keyboard_arrow_down_rounded,
                      size: 18,
                      color: labelColor,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _summaryText(Locale locale) {
    final parts = <String>[];
    final stepCount = thinkingCount + toolCount;
    if (stepCount > 0) {
      parts.add(
        AppTextLocalizer.choose(
          zh: '$stepCount 步',
          en: '$stepCount ${stepCount == 1 ? 'step' : 'steps'}',
          locale: locale,
        ),
      );
    }
    final trimmed = latestProcessSummary.trim();
    if (trimmed.isNotEmpty) {
      parts.add(trimmed);
    }
    return parts.join(' · ');
  }
}

class _AgentThinkingActivityRow extends StatefulWidget {
  const _AgentThinkingActivityRow({
    super.key,
    required this.message,
    this.onLayoutChanged,
  });

  final ChatMessageModel message;
  final VoidCallback? onLayoutChanged;

  @override
  State<_AgentThinkingActivityRow> createState() =>
      _AgentThinkingActivityRowState();
}

class _AgentThinkingActivityRowState extends State<_AgentThinkingActivityRow> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final cardData = widget.message.cardData ?? const <String, dynamic>{};
    final content = (cardData['thinkingContent'] ?? '').toString().trim();
    final stage = _asInt(cardData['stage']) ?? 1;
    final isLoading =
        _asBool(cardData['isLoading']) ?? (stage != 4 && stage != 5);
    if (content.isEmpty && !isLoading) {
      return const SizedBox.shrink();
    }
    final label = AppTextLocalizer.choose(
      zh: isLoading ? '思考中' : '思考',
      en: isLoading ? 'Thinking' : 'Thought',
      locale: Localizations.localeOf(context),
    );
    final preview = _firstMeaningfulLine(content);
    final statusColor = isLoading
        ? palette.accentPrimary
        : palette.textTertiary;

    return Padding(
      padding: const EdgeInsets.only(top: 2, bottom: 2),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: content.isEmpty ? null : _toggleExpanded,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 18,
                      height: 18,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: statusColor.withValues(
                          alpha: context.isDarkTheme ? 0.14 : 0.12,
                        ),
                      ),
                      child: Center(
                        child: isLoading
                            ? SizedBox(
                                width: 8,
                                height: 8,
                                child: CircularProgressIndicator(
                                  strokeWidth: 1.4,
                                  valueColor: AlwaysStoppedAnimation<Color>(
                                    statusColor,
                                  ),
                                ),
                              )
                            : Icon(
                                Icons.psychology_alt_outlined,
                                size: 11,
                                color: statusColor,
                              ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            label,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                              height: 1.1,
                            ),
                          ),
                          if (!_expanded && preview.isNotEmpty)
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
                    if (content.isNotEmpty)
                      AnimatedRotation(
                        turns: _expanded ? 0 : -0.25,
                        duration: const Duration(milliseconds: 160),
                        child: Icon(
                          Icons.keyboard_arrow_down_rounded,
                          size: 16,
                          color: palette.textTertiary,
                        ),
                      ),
                  ],
                ),
                if (_expanded) _ThinkingDetail(content: content),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _toggleExpanded() {
    setState(() {
      _expanded = !_expanded;
    });
    widget.onLayoutChanged?.call();
  }
}

class _ThinkingDetail extends StatelessWidget {
  const _ThinkingDetail({required this.content});

  final String content;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(top: 8, left: 26),
      child: Container(
        constraints: const BoxConstraints(maxHeight: 220),
        decoration: BoxDecoration(
          border: Border(
            left: BorderSide(
              color: palette.borderSubtle.withValues(alpha: 0.75),
              width: 1,
            ),
          ),
        ),
        child: SingleChildScrollView(
          physics: const ClampingScrollPhysics(),
          padding: const EdgeInsets.only(left: 10, right: 2),
          child: Text(
            content,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 11,
              fontWeight: FontWeight.w400,
              height: 1.35,
              fontFamily: 'monospace',
            ),
          ),
        ),
      ),
    );
  }
}

class _ProcessStatusPill extends StatelessWidget {
  const _ProcessStatusPill({
    required this.label,
    required this.isActiveRun,
    required this.expanded,
  });

  final String label;
  final bool isActiveRun;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = isActiveRun
        ? palette.accentPrimary
        : (expanded ? palette.textSecondary : palette.textTertiary);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.12 : 0.08),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w600,
            color: color,
            height: 1,
          ),
        ),
      ),
    );
  }
}

bool _isThinkingProcessMessage(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString() == 'deep_thinking';
}

String _firstMeaningfulLine(String value) {
  final line = value
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split('\n')
      .map((item) => item.replaceAll(RegExp(r'\s+'), ' ').trim())
      .firstWhere((item) => item.isNotEmpty, orElse: () => '');
  if (line.length <= 96) {
    return line;
  }
  return '${line.substring(0, 95).trimRight()}…';
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    final asDouble = value.toDouble();
    if (asDouble.isFinite && asDouble == asDouble.truncateToDouble()) {
      return value.toInt();
    }
  }
  if (value is String) {
    return int.tryParse(value.trim());
  }
  return null;
}

bool? _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  if (value is String) {
    final normalized = value.trim().toLowerCase();
    if (normalized == 'true') {
      return true;
    }
    if (normalized == 'false') {
      return false;
    }
  }
  return null;
}
