import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/utils/agent_activity_compactor.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_tool_activity_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart'
    show showAgentToolDetailSheet;
import 'package:ui/features/home/pages/command_overlay/widgets/cards/card_widget_factory.dart'
    show OnBeforeTaskExecute, OnRequestAuthorize;
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/services/agent_tool_card_policy.dart' as tool_policy;
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
  static const double _compactProcessMaxWidthFactor = 0.76;
  static const double _compactProcessMaxWidth = 360;
  static const double _compactProcessMinWidth = 148;
  static const double _processFontSize = 12;
  static const double _thinkingFontSize = 11;

  late final AnimationController _expandController;
  late final Animation<double> _sizeFactor;
  late final Animation<double> _opacity;
  late final Animation<double> _lift;
  bool _isNotifyingParentDuringAnimation = false;
  bool _expandThinkingOnNextOpen = false;
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
    final showRunLogButton =
        !widget.group.isActiveRun && widget.group.runLogId.trim().isNotEmpty;
    final directProcessAction = _resolveDirectProcessAction(processMessages);
    final showProcessToggle = hasProcessMessages && directProcessAction == null;

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
          outputSegmentCount: widget.group.outputSegmentCount,
          latestProcessSummary: _latestProcessSummary(context, processMessages),
          isRunLogOnly: isRunLogOnly,
          onTap: hasProcessMessages
              ? (directProcessAction ??
                    () => _toggleProcessSection(processMessages))
              : isRunLogOnly
              ? () => _openRunLog(context)
              : null,
          showToggle: showProcessToggle,
          showRunLogButton: showRunLogButton,
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
    showRunLogTimelineSheet(context, runId: runLogId);
  }

  VoidCallback? _resolveDirectProcessAction(
    List<ChatMessageModel> processMessages,
  ) {
    if (widget.expanded || processMessages.isEmpty) {
      return null;
    }
    final processItems = _compactor.compact(processMessages);
    if (processItems.length != 1) {
      return null;
    }
    final item = processItems.single;
    final activity = item.activity;
    if (activity != null) {
      if (activity.stepCount > 1 &&
          activity.kind == AgentToolActivityKind.vlm) {
        return null;
      }
      final step = activity.steps.last;
      final cardData = step.message.cardData;
      if (cardData == null ||
          (cardData['type'] ?? '').toString() != kAgentToolSummaryCardType) {
        return null;
      }
      return () => _openSingleActivityStep(context, step);
    }
    final message = item.message!;
    final cardData = message.cardData;
    if ((cardData?['type'] ?? '').toString() == 'deep_thinking') {
      final content = (cardData?['thinkingContent'] ?? '').toString().trim();
      if (content.isEmpty) {
        return null;
      }
      return () {
        setState(() {
          _expandThinkingOnNextOpen = true;
        });
        widget.onToggleExpanded();
      };
    }
    if (cardData != null &&
        (cardData['type'] ?? '').toString() == kAgentToolSummaryCardType) {
      return () => showAgentToolDetailSheet(context, cardData: cardData);
    }
    return null;
  }

  void _openSingleActivityStep(
    BuildContext context,
    AgentToolActivityStep step,
  ) {
    final cardData = step.message.cardData ?? const <String, dynamic>{};
    final kind = tool_policy.AgentToolCardPolicy.activityKindFor(cardData);
    final runLogRef = tool_policy.AgentToolCardPolicy.runLogRef(
      cardData,
      message: step.message,
    );
    if (kind == tool_policy.AgentToolActivityKind.vlm && runLogRef.hasStep) {
      showRunLogStepDetailSheet(
        context,
        runId: runLogRef.runLogId,
        cardId: runLogRef.cardId,
        title: resolveAgentToolTitle(
          cardData,
          locale: Localizations.localeOf(context),
        ),
      );
      return;
    }
    showAgentToolDetailSheet(context, cardData: cardData);
  }

  void _toggleProcessSection(List<ChatMessageModel> processMessages) {
    if (!widget.expanded && _hasThinkingContent(processMessages)) {
      setState(() {
        _expandThinkingOnNextOpen = true;
      });
    }
    widget.onToggleExpanded();
  }

  bool _hasThinkingContent(List<ChatMessageModel> processMessages) {
    for (final message in processMessages) {
      final cardData = message.cardData;
      if ((cardData?['type'] ?? '').toString() != 'deep_thinking') {
        continue;
      }
      final content = (cardData?['thinkingContent'] ?? '').toString().trim();
      if (content.isNotEmpty) {
        return true;
      }
    }
    return false;
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
    final singleProcessItem = processItems.length == 1;

    return AnimatedBuilder(
      animation: _expandController,
      child: Column(
        key: ValueKey('agent-run-process-${widget.group.taskId}'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: processItems
            .asMap()
            .entries
            .map((entry) {
              final index = entry.key;
              final item = entry.value;
              final activity = item.activity;
              if (activity != null) {
                return AgentToolActivityCard(
                  key: ValueKey(
                    'agent-run-${widget.group.taskId}-activity-$index-${activity.id}-${widget.expanded}',
                  ),
                  activity: activity,
                  compactSurface: true,
                  initiallyExpanded:
                      singleProcessItem &&
                      activity.stepCount > 1 &&
                      activity.kind == AgentToolActivityKind.vlm,
                  onLayoutChanged: widget.onStreamingTextLayoutChanged,
                );
              }
              final message = item.message!;
              if (_isThinkingProcessMessage(message)) {
                final initiallyExpanded = _expandThinkingOnNextOpen;
                if (initiallyExpanded) {
                  WidgetsBinding.instance.addPostFrameCallback((_) {
                    if (!mounted || !_expandThinkingOnNextOpen) {
                      return;
                    }
                    setState(() {
                      _expandThinkingOnNextOpen = false;
                    });
                  });
                }
                return _AgentThinkingActivityRow(
                  key: ValueKey(
                    'agent-run-${widget.group.taskId}-thinking-$index-${message.id}-${widget.expanded}',
                  ),
                  message: message,
                  initiallyExpanded: initiallyExpanded,
                  onLayoutChanged: widget.onStreamingTextLayoutChanged,
                );
              }
              return MessageBubble(
                key: ValueKey(
                  'agent-run-${widget.group.taskId}-process-message-$index-${message.id}-${widget.expanded}',
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

  String _latestProcessSummary(
    BuildContext context,
    List<ChatMessageModel> processMessages,
  ) {
    final locale = Localizations.localeOf(context);
    for (final message in processMessages.reversed) {
      final cardData = message.cardData;
      final cardType = (cardData?['type'] ?? '').toString();
      if (cardType == kAgentToolSummaryCardType && cardData != null) {
        final title = resolveAgentToolTitle(cardData, locale: locale).trim();
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
    required this.outputSegmentCount,
    required this.latestProcessSummary,
    required this.isRunLogOnly,
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
  final int outputSegmentCount;
  final String latestProcessSummary;
  final bool isRunLogOnly;
  final VoidCallback? onTap;
  final bool showToggle;
  final bool showRunLogButton;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final label = AppTextLocalizer.choose(
      zh: '步骤',
      en: 'Steps',
      locale: locale,
    );
    if (isRunLogOnly) {
      return _RunLogOnlySummaryHeader(
        taskId: taskId,
        runLogId: runLogId,
        label: label,
        onTap: onTap,
      );
    }
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
                                fontSize:
                                    _AgentRunGroupMessageState._processFontSize,
                                fontWeight: FontWeight.w600,
                                color: labelColor,
                                fontFamily: 'PingFang SC',
                                letterSpacing: 0,
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
                                  fontSize: _AgentRunGroupMessageState
                                      ._processFontSize,
                                  fontWeight: FontWeight.w500,
                                  color: palette.textTertiary,
                                  letterSpacing: 0,
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
                  _RunLogHeaderButton(
                    key: ValueKey('agent-run-runlog-$taskId'),
                    taskId: taskId,
                    runLogId: runLogId,
                    iconColor: labelColor,
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
    if (outputSegmentCount > 0) {
      parts.add(
        AppTextLocalizer.choose(
          zh: '$outputSegmentCount 段输出',
          en: '$outputSegmentCount output ${outputSegmentCount == 1 ? 'segment' : 'segments'}',
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

class _RunLogOnlySummaryHeader extends StatelessWidget {
  const _RunLogOnlySummaryHeader({
    required this.taskId,
    required this.runLogId,
    required this.label,
    required this.onTap,
  });

  final String taskId;
  final String runLogId;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final labelColor = palette.textTertiary;
    final statusLabel = AppTextLocalizer.choose(
      zh: '无可展开步骤',
      en: 'No steps',
      locale: locale,
    );
    final resolvedRunLogId = runLogId.trim().isEmpty ? taskId : runLogId.trim();

    return Padding(
      padding: const EdgeInsets.only(top: 6, bottom: 1),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 2, 2, 2),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ValueListenableBuilder<AgentAvatarState>(
                  valueListenable: AgentAvatarService.avatarStateNotifier,
                  builder: (context, state, _) {
                    return AgentAvatarCircle(
                      key: ValueKey('agent-run-avatar-$taskId'),
                      state: state,
                      size: 24,
                    );
                  },
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Row(
                    children: [
                      Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: _AgentRunGroupMessageState._processFontSize,
                          fontWeight: FontWeight.w600,
                          color: labelColor,
                          fontFamily: 'PingFang SC',
                          letterSpacing: 0,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          statusLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize:
                                _AgentRunGroupMessageState._processFontSize,
                            fontWeight: FontWeight.w500,
                            color: palette.textTertiary,
                            letterSpacing: 0,
                            height: 1.1,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 6),
                _RunLogHeaderButton(
                  key: ValueKey('agent-run-runlog-$taskId'),
                  taskId: taskId,
                  runLogId: resolvedRunLogId,
                  iconColor: labelColor,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RunLogHeaderButton extends StatelessWidget {
  const _RunLogHeaderButton({
    super.key,
    required this.taskId,
    required this.runLogId,
    required this.iconColor,
  });

  final String taskId;
  final String runLogId;
  final Color iconColor;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final resolvedRunLogId = runLogId.trim().isEmpty ? taskId : runLogId.trim();

    return Tooltip(
      message: AppTextLocalizer.text('查看执行记录', locale: locale),
      child: InkResponse(
        onTap: () => showRunLogTimelineSheet(context, runId: resolvedRunLogId),
        radius: 18,
        child: Icon(Icons.route_rounded, size: 16, color: iconColor),
      ),
    );
  }
}

class _AgentThinkingActivityRow extends StatefulWidget {
  const _AgentThinkingActivityRow({
    super.key,
    required this.message,
    this.initiallyExpanded = false,
    this.onLayoutChanged,
  });

  final ChatMessageModel message;
  final bool initiallyExpanded;
  final VoidCallback? onLayoutChanged;

  @override
  State<_AgentThinkingActivityRow> createState() =>
      _AgentThinkingActivityRowState();
}

class _AgentThinkingActivityRowState extends State<_AgentThinkingActivityRow> {
  late bool _expanded = widget.initiallyExpanded;

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
        ? const Color(0xFF8B5CF6)
        : palette.textTertiary;

    return Align(
      alignment: Alignment.centerLeft,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final maxWidth =
              (constraints.maxWidth *
                      _AgentRunGroupMessageState._compactProcessMaxWidthFactor)
                  .clamp(
                    _AgentRunGroupMessageState._compactProcessMinWidth,
                    _AgentRunGroupMessageState._compactProcessMaxWidth,
                  )
                  .toDouble();
          return ConstrainedBox(
            key: ValueKey('agent-thinking-activity-row-${widget.message.id}'),
            constraints: BoxConstraints(
              minWidth: _AgentRunGroupMessageState._compactProcessMinWidth,
              maxWidth: maxWidth,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  borderRadius: BorderRadius.circular(8),
                  onTap: content.isEmpty ? null : _toggleExpanded,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(2, 4, 4, 4),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: isLoading
                                  ? SizedBox(
                                      width: 12,
                                      height: 12,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 1.4,
                                        valueColor:
                                            AlwaysStoppedAnimation<Color>(
                                              statusColor,
                                            ),
                                      ),
                                    )
                                  : Icon(
                                      Icons.psychology_alt_outlined,
                                      size: 13,
                                      color: statusColor,
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
                                      color: statusColor,
                                      fontSize: _AgentRunGroupMessageState
                                          ._thinkingFontSize,
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: 0,
                                      height: 1.15,
                                    ),
                                  ),
                                  if (!_expanded && preview.isNotEmpty)
                                    Padding(
                                      padding: const EdgeInsets.only(top: 2),
                                      child: Text(
                                        preview,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          color: palette.textTertiary,
                                          fontSize: _AgentRunGroupMessageState
                                              ._thinkingFontSize,
                                          fontWeight: FontWeight.w500,
                                          letterSpacing: 0,
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
            ),
          );
        },
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
              color: palette.textTertiary,
              fontSize: _AgentRunGroupMessageState._thinkingFontSize,
              fontWeight: FontWeight.w500,
              fontStyle: FontStyle.italic,
              letterSpacing: 0,
              height: 1.28,
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
            fontSize: _AgentRunGroupMessageState._processFontSize,
            fontWeight: FontWeight.w600,
            color: color,
            letterSpacing: 0,
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
