import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_tool_activity_strip.dart'
    show ToolActivityRow;
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/floating_overlay_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

String _t(String text) => AppTextLocalizer.text(text);

String _runningAgentsSemanticsLabel(int count, {required bool collapsed}) {
  return AppTextLocalizer.choose(
    zh: collapsed ? '运行中的 Agent：$count 个。轻点展开摘要。' : '运行中的 Agent：$count 个。',
    en: collapsed
        ? 'Running Agents: $count. Tap to expand summary.'
        : 'Running Agents: $count.',
  );
}

String _runningAgentsTitle(int count) {
  return AppTextLocalizer.choose(
    zh: '运行中 $count 个 Agent',
    en: '$count Agents running',
  );
}

class AgentRunMonitorOverlay extends StatefulWidget {
  const AgentRunMonitorOverlay({super.key});

  @override
  State<AgentRunMonitorOverlay> createState() => _AgentRunMonitorOverlayState();
}

class _AgentRunMonitorOverlayState extends State<AgentRunMonitorOverlay> {
  static const _positionXKey = 'agent_run_monitor_capsule_x';
  static const _positionYKey = 'agent_run_monitor_capsule_y';
  static const _collapsedKey = 'agent_run_monitor_capsule_collapsed';
  static const _capsuleHitWidth = 40.0;
  static const _capsuleHitHeight = 34.0;
  static const _expandedCapsuleMaxWidth = 320.0;
  static const _expandedCapsuleMinWidth = 236.0;
  static const _expandedCapsuleMaxHeight = 244.0;
  static const _edgeMargin = 8.0;

  final List<_AgentRunSnapshot> _runs = [];
  final Map<String, _AgentRunEventSummary> _latestEvents = {};
  StreamSubscription<Map<String, dynamic>>? _stateSub;
  Timer? _pollTimer;
  Offset? _capsulePosition;
  bool _refreshing = false;
  bool _dragging = false;
  bool _capsuleCollapsed = true;
  bool _floatingOverlayEnabled = FloatingOverlayService.isEnabled;

  @override
  void initState() {
    super.initState();
    _restoreCapsulePosition();
    FloatingOverlayService.enabledListenable.addListener(
      _handleFloatingOverlayPreferenceChanged,
    );
    unawaited(FloatingOverlayService.loadEnabled());
    _stateSub = AssistsMessageService.agentRunStateChangedStream.listen(
      (_) => unawaited(_refresh()),
    );
    AssistsMessageService.setOnAgentStreamEventCallback(_handleAgentEvent);
    unawaited(_refresh());
    _pollTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => unawaited(_refresh()),
    );
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _stateSub?.cancel();
    FloatingOverlayService.enabledListenable.removeListener(
      _handleFloatingOverlayPreferenceChanged,
    );
    AssistsMessageService.removeOnAgentStreamEventCallback(_handleAgentEvent);
    super.dispose();
  }

  void _handleFloatingOverlayPreferenceChanged() {
    if (!mounted) return;
    final enabled = FloatingOverlayService.isEnabled;
    setState(() {
      _floatingOverlayEnabled = enabled;
      if (!enabled) {
        _runs.clear();
      }
    });
    if (enabled) {
      unawaited(_refresh());
    }
  }

  void _restoreCapsulePosition() {
    try {
      final x = StorageService.getDouble(_positionXKey);
      final y = StorageService.getDouble(_positionYKey);
      if (x != null && y != null) {
        _capsulePosition = Offset(x, y);
      }
      _capsuleCollapsed =
          StorageService.getBool(_collapsedKey, defaultValue: true) ?? true;
    } catch (_) {
      _capsulePosition = null;
    }
  }

  Future<void> _saveCapsulePosition() async {
    final position = _capsulePosition;
    if (position == null) return;
    try {
      await StorageService.setDouble(_positionXKey, position.dx);
      await StorageService.setDouble(_positionYKey, position.dy);
    } catch (_) {
      // Position persistence is best-effort. The monitor should still work.
    }
  }

  Future<void> _saveCapsuleCollapsed() async {
    try {
      await StorageService.setBool(_collapsedKey, _capsuleCollapsed);
    } catch (_) {
      // Collapse state persistence is best-effort.
    }
  }

  void _handleAgentEvent(AgentStreamEvent event) {
    if (!_floatingOverlayEnabled) return;
    if (mounted) {
      setState(() {
        _latestEvents[event.taskId] = _AgentRunEventSummary.fromEvent(event);
      });
    } else {
      _latestEvents[event.taskId] = _AgentRunEventSummary.fromEvent(event);
    }
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (!_floatingOverlayEnabled) return;
    if (_refreshing) return;
    _refreshing = true;
    try {
      final rows = await AssistsMessageService.listActiveAgentRuns();
      if (!mounted) return;
      final snapshots = rows
          .map(_AgentRunSnapshot.fromMap)
          .toList(growable: false);
      final activeTaskIds = snapshots.map((run) => run.taskId).toSet();
      setState(() {
        _runs
          ..clear()
          ..addAll(snapshots);
        _latestEvents.removeWhere(
          (taskId, _) => !activeTaskIds.contains(taskId),
        );
      });
    } finally {
      _refreshing = false;
    }
  }

  void _setCapsuleCollapsed(bool collapsed) {
    if (_capsuleCollapsed == collapsed) return;
    setState(() => _capsuleCollapsed = collapsed);
    unawaited(_saveCapsuleCollapsed());
  }

  void _openRun(_AgentRunSnapshot run) {
    final conversationId = run.conversationId;
    if (conversationId == null || conversationId.isEmpty) return;
    final requestKey = DateTime.now().microsecondsSinceEpoch.toString();
    GoRouterManager.go(
      Uri(
        path: '/home/chat',
        queryParameters: {
          'conversationId': conversationId,
          'mode': run.conversationMode,
          'requestKey': requestKey,
        },
      ).toString(),
    );
  }

  Future<void> _disableFloatingOverlay() async {
    if (!_floatingOverlayEnabled) return;
    setState(() => _floatingOverlayEnabled = false);
    final success = await FloatingOverlayService.setEnabled(false);
    if (!mounted) return;
    if (!success) {
      setState(() => _floatingOverlayEnabled = true);
      _showSnack(_t('关闭悬浮球失败'));
      return;
    }
    _showSnack(_t('悬浮球已关闭，可在设置里重新开启'));
  }

  void _showSnack(String message) {
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  Rect _dragBounds(Size size, EdgeInsets padding, Size capsuleSize) {
    final maxLeft = math.max(
      _edgeMargin,
      size.width - capsuleSize.width - _edgeMargin,
    );
    final maxTop = math.max(
      padding.top + _edgeMargin,
      size.height - capsuleSize.height - padding.bottom - _edgeMargin,
    );
    return Rect.fromLTRB(
      _edgeMargin,
      padding.top + _edgeMargin,
      maxLeft,
      maxTop,
    );
  }

  Size _capsuleSize(Size size) {
    if (_capsuleCollapsed) {
      return const Size(_capsuleHitWidth, _capsuleHitHeight);
    }
    final width = math.min(
      _expandedCapsuleMaxWidth,
      size.width - _edgeMargin * 2,
    );
    final visibleRows = math.max(1, math.min(_runs.length, 4));
    final listHeight = visibleRows * 37.0 + math.max(0, visibleRows - 1) * 5.0;
    final desiredHeight = 16.0 + 30.0 + 7.0 + listHeight;
    final maxHeight = math.min(size.height * 0.42, _expandedCapsuleMaxHeight);
    return Size(
      math.max(_expandedCapsuleMinWidth, width),
      math.min(math.max(96.0, desiredHeight), maxHeight),
    );
  }

  Offset _defaultCapsulePosition(
    Size size,
    EdgeInsets padding,
    Size capsuleSize,
  ) {
    final bounds = _dragBounds(size, padding, capsuleSize);
    return Offset(bounds.right, padding.top + 10).clampTo(bounds);
  }

  Offset _resolvedCapsulePosition(
    Size size,
    EdgeInsets padding,
    Size capsuleSize,
  ) {
    final bounds = _dragBounds(size, padding, capsuleSize);
    return (_capsulePosition ??
            _defaultCapsulePosition(size, padding, capsuleSize))
        .clampTo(bounds);
  }

  void _moveCapsule(DragUpdateDetails details, Rect bounds, Offset fallback) {
    final current = _capsulePosition ?? fallback;
    setState(() {
      _dragging = true;
      _capsulePosition = (current + details.delta).clampTo(bounds);
    });
  }

  void _endDrag() {
    if (!_dragging) return;
    setState(() => _dragging = false);
    unawaited(_saveCapsulePosition());
  }

  @override
  Widget build(BuildContext context) {
    if (!_floatingOverlayEnabled) return const SizedBox.shrink();
    return Positioned.fill(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final mediaSize = MediaQuery.sizeOf(context);
          final size = Size(
            constraints.maxWidth.isFinite
                ? constraints.maxWidth
                : mediaSize.width,
            constraints.maxHeight.isFinite
                ? constraints.maxHeight
                : mediaSize.height,
          );
          final padding = MediaQuery.viewPaddingOf(context);
          final capsuleSize = _capsuleSize(size);
          final bounds = _dragBounds(size, padding, capsuleSize);
          final position = _resolvedCapsulePosition(size, padding, capsuleSize);
          return Stack(
            children: [
              Positioned(
                left: position.dx,
                top: position.dy,
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _capsuleCollapsed
                      ? () {
                          unawaited(_refresh());
                          _setCapsuleCollapsed(false);
                        }
                      : null,
                  onPanStart: (_) {
                    setState(() {
                      _dragging = true;
                      _capsulePosition = position;
                    });
                  },
                  onPanUpdate: (details) =>
                      _moveCapsule(details, bounds, position),
                  onPanEnd: (_) => _endDrag(),
                  onPanCancel: _endDrag,
                  child: Semantics(
                    button: true,
                    label: _runs.isEmpty
                        ? _t('Agent 后端空闲。轻点打开管理面板。')
                        : _runningAgentsSemanticsLabel(
                            _runs.length,
                            collapsed: _capsuleCollapsed,
                          ),
                    child: SizedBox(
                      width: capsuleSize.width,
                      height: capsuleSize.height,
                      child: _capsuleCollapsed
                          ? Center(
                              child: _AgentRunMiniCapsule(
                                count: _runs.length,
                                dragging: _dragging,
                              ),
                            )
                          : _AgentRunExpandedCapsule(
                              runs: _runs,
                              latestEvents: _latestEvents,
                              dragging: _dragging,
                              onCollapse: () => _setCapsuleCollapsed(true),
                              onOpenRun: _openRun,
                              onDisable: () =>
                                  unawaited(_disableFloatingOverlay()),
                            ),
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _AgentRunMiniCapsule extends StatelessWidget {
  const _AgentRunMiniCapsule({required this.count, required this.dragging});

  final int count;
  final bool dragging;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final idle = count <= 0;
    final statusColor = idle
        ? palette.textTertiary.withValues(alpha: 0.72)
        : palette.accentPrimary;
    final textColor = idle ? palette.textSecondary : palette.textPrimary;
    return AnimatedScale(
      scale: dragging ? 1.06 : 1,
      duration: const Duration(milliseconds: 140),
      curve: Curves.easeOutCubic,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        curve: Curves.easeOutCubic,
        constraints: const BoxConstraints(minWidth: 34),
        height: 26,
        padding: const EdgeInsets.symmetric(horizontal: 7),
        decoration: BoxDecoration(
          color: palette.surfacePrimary.withValues(alpha: 0.96),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: palette.accentPrimary.withValues(
              alpha: dragging ? 0.5 : 0.3,
            ),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: dragging ? 0.18 : 0.12),
              blurRadius: dragging ? 18 : 14,
              offset: Offset(0, dragging ? 9 : 6),
            ),
          ],
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _AgentRunStatusDot(color: statusColor, size: 6),
            const SizedBox(width: 5),
            Text(
              count > 99 ? '99+' : '$count',
              style: TextStyle(
                color: textColor,
                fontSize: 12,
                fontWeight: FontWeight.w900,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentRunExpandedCapsule extends StatelessWidget {
  const _AgentRunExpandedCapsule({
    required this.runs,
    required this.latestEvents,
    required this.dragging,
    required this.onCollapse,
    required this.onOpenRun,
    required this.onDisable,
  });

  final List<_AgentRunSnapshot> runs;
  final Map<String, _AgentRunEventSummary> latestEvents;
  final bool dragging;
  final VoidCallback onCollapse;
  final ValueChanged<_AgentRunSnapshot> onOpenRun;
  final VoidCallback onDisable;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final hasActiveRuns = runs.isNotEmpty;
    return AnimatedScale(
      scale: dragging ? 1.02 : 1,
      duration: const Duration(milliseconds: 140),
      curve: Curves.easeOutCubic,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        curve: Curves.easeOutCubic,
        padding: const EdgeInsets.fromLTRB(10, 8, 6, 8),
        decoration: BoxDecoration(
          color: palette.surfacePrimary.withValues(alpha: 0.97),
          borderRadius: BorderRadius.circular(18),
          border: Border.all(
            color: palette.accentPrimary.withValues(
              alpha: dragging ? 0.5 : 0.28,
            ),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: dragging ? 0.18 : 0.12),
              blurRadius: dragging ? 18 : 14,
              offset: Offset(0, dragging ? 9 : 6),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                _AgentRunPulse(color: palette.accentPrimary, size: 18),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    hasActiveRuns
                        ? _runningAgentsTitle(runs.length)
                        : _t('当前没有后端任务'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w900,
                      height: 1.1,
                    ),
                  ),
                ),
                Semantics(
                  button: true,
                  label: _t('隐藏悬浮球'),
                  child: IconButton(
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints.tightFor(
                      width: 28,
                      height: 28,
                    ),
                    onPressed: onDisable,
                    icon: Icon(
                      Icons.visibility_off_outlined,
                      size: 17,
                      color: palette.textTertiary,
                    ),
                  ),
                ),
                Semantics(
                  button: true,
                  label: _t('收起'),
                  child: IconButton(
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints.tightFor(
                      width: 30,
                      height: 30,
                    ),
                    onPressed: onCollapse,
                    icon: Icon(
                      Icons.keyboard_arrow_right_rounded,
                      size: 20,
                      color: palette.textTertiary,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 7),
            Flexible(
              child: hasActiveRuns
                  ? ListView.separated(
                      padding: EdgeInsets.zero,
                      itemCount: runs.length,
                      separatorBuilder: (_, __) => const SizedBox(height: 5),
                      itemBuilder: (context, index) {
                        final run = runs[index];
                        return _AgentRunCompactRow(
                          run: run,
                          latestEvent: latestEvents[run.taskId],
                          onTap: run.conversationId == null
                              ? null
                              : () => onOpenRun(run),
                        );
                      },
                    )
                  : const _AgentRunIdleRow(),
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentRunCompactRow extends StatelessWidget {
  const _AgentRunCompactRow({
    required this.run,
    required this.latestEvent,
    required this.onTap,
  });

  final _AgentRunSnapshot run;
  final _AgentRunEventSummary? latestEvent;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final displayCard = _agentRunToolActivityCard(run, latestEvent);
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          height: 37,
          decoration: BoxDecoration(
            color: palette.surfaceSecondary.withValues(alpha: 0.78),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Center(
              child: ToolActivityRow(
                card: displayCard,
                showRunLogButton: false,
                trailing: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    run.elapsedCompactLabel,
                    maxLines: 1,
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 9,
                      fontWeight: FontWeight.w800,
                      fontFeatures: const [FontFeature.tabularFigures()],
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

class _AgentRunIdleRow extends StatelessWidget {
  const _AgentRunIdleRow();

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      height: 37,
      padding: const EdgeInsets.symmetric(horizontal: 11),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.78),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        children: [
          _AgentRunStatusDot(
            color: palette.textTertiary.withValues(alpha: 0.72),
            size: 6,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _t('当前没有任何 Agent'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 11.5,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _AgentRunStatusDot extends StatelessWidget {
  const _AgentRunStatusDot({required this.color, this.size = 6});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: 0.28),
            blurRadius: 5,
            spreadRadius: 1,
          ),
        ],
      ),
    );
  }
}

class _AgentRunPulse extends StatelessWidget {
  const _AgentRunPulse({required this.color, this.size = 14});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.center,
      children: [
        SizedBox(
          width: size,
          height: size,
          child: CircularProgressIndicator(
            strokeWidth: 2,
            valueColor: AlwaysStoppedAnimation<Color>(color),
          ),
        ),
        Container(
          width: size * 0.34,
          height: size * 0.34,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
      ],
    );
  }
}

class _AgentRunEventSummary {
  const _AgentRunEventSummary({
    required this.label,
    required this.icon,
    this.isError = false,
    this.toolCard,
  });

  final String label;
  final IconData icon;
  final bool isError;
  final Map<String, dynamic>? toolCard;

  factory _AgentRunEventSummary.fromEvent(AgentStreamEvent event) {
    final raw = event.raw;
    final toolName = (raw['toolName'] ?? raw['name'] ?? '').toString().trim();
    final message = _firstNonEmpty([
      event.errorMessage,
      event.text,
      event.thinking,
      (raw['summary'] ?? '').toString(),
      (raw['message'] ?? '').toString(),
      (raw['inputPreview'] ?? '').toString(),
      (raw['resultPreview'] ?? '').toString(),
    ]);
    final toolCard = _toolCardFromEvent(event, message);
    return switch (event.kind) {
      AgentStreamEventKind.thinkingStarted => _AgentRunEventSummary(
        label: _t('正在思考'),
        icon: Icons.psychology_alt_outlined,
      ),
      AgentStreamEventKind.thinkingSnapshot => _AgentRunEventSummary(
        label: message.isEmpty ? _t('正在整理方案') : message,
        icon: Icons.psychology_alt_outlined,
      ),
      AgentStreamEventKind.textSnapshot => _AgentRunEventSummary(
        label: message.isEmpty ? _t('正在输出') : message,
        icon: Icons.notes_rounded,
      ),
      AgentStreamEventKind.toolStarted => _AgentRunEventSummary(
        label: toolName.isEmpty ? _t('开始调用工具') : _t('调用 $toolName'),
        icon: Icons.build_circle_outlined,
        toolCard: toolCard,
      ),
      AgentStreamEventKind.toolProgress => _AgentRunEventSummary(
        label: message.isEmpty
            ? (toolName.isEmpty ? _t('工具执行中') : _t('$toolName 执行中'))
            : message,
        icon: Icons.sync_rounded,
        toolCard: toolCard,
      ),
      AgentStreamEventKind.toolCompleted => _AgentRunEventSummary(
        label: toolName.isEmpty ? _t('工具完成') : _t('$toolName 完成'),
        icon: Icons.check_circle_outline_rounded,
        toolCard: toolCard,
      ),
      AgentStreamEventKind.permissionRequired => _AgentRunEventSummary(
        label: _t('等待权限确认'),
        icon: Icons.verified_user_outlined,
      ),
      AgentStreamEventKind.clarifyRequired => _AgentRunEventSummary(
        label: event.question.isEmpty ? _t('等待补充信息') : event.question,
        icon: Icons.help_outline_rounded,
      ),
      AgentStreamEventKind.error => _AgentRunEventSummary(
        label: message.isEmpty ? _t('运行出错') : message,
        icon: Icons.error_outline_rounded,
        isError: true,
      ),
      AgentStreamEventKind.completed => _AgentRunEventSummary(
        label: _t('即将完成'),
        icon: Icons.done_all_rounded,
      ),
    };
  }

  static Map<String, dynamic>? _toolCardFromEvent(
    AgentStreamEvent event,
    String message,
  ) {
    final raw = <String, dynamic>{...event.raw};
    if (message.isNotEmpty) {
      raw.putIfAbsent('summary', () => message);
      raw.putIfAbsent('progress', () => message);
    }
    return switch (event.kind) {
      AgentStreamEventKind.toolStarted => buildAgentToolActivityCard(
        raw,
        taskId: event.taskId,
      ),
      AgentStreamEventKind.toolProgress => buildAgentToolActivityCard(
        raw,
        taskId: event.taskId,
      ),
      AgentStreamEventKind.toolCompleted => buildAgentToolActivityCard(
        raw,
        taskId: event.taskId,
        defaultStatus: event.success ? 'success' : 'error',
      ),
      _ => null,
    };
  }

  static String _firstNonEmpty(List<String> values) {
    for (final value in values) {
      final trimmed = value.trim();
      if (trimmed.isNotEmpty) {
        return trimmed.replaceAll(RegExp(r'\s+'), ' ');
      }
    }
    return '';
  }
}

Map<String, dynamic> _agentRunToolActivityCard(
  _AgentRunSnapshot run,
  _AgentRunEventSummary? latestEvent,
) {
  final card = latestEvent?.toolCard ?? run.toolActivityCard;
  if (card != null) {
    return card;
  }
  final summary = latestEvent?.label ?? run.compactSummary;
  return buildAgentToolActivityCard(
    {
      'toolName': run.conversationMode,
      'displayName': run.conversationModeLabel,
      'toolTitle': summary,
      'summary': summary,
      'toolType': run.conversationMode == 'subagent' ? 'subagent' : 'builtin',
    },
    taskId: run.taskId,
    defaultStatus: latestEvent?.isError == true ? 'error' : 'running',
  );
}

class _AgentRunSnapshot {
  const _AgentRunSnapshot({
    required this.taskId,
    required this.conversationId,
    required this.conversationMode,
    required this.startedAtMillis,
    required this.elapsedMillis,
    required this.toolName,
    required this.toolSummary,
    required this.userMessage,
    required this.toolActivityCard,
  });

  final String taskId;
  final String? conversationId;
  final String conversationMode;
  final int startedAtMillis;
  final int elapsedMillis;
  final String toolName;
  final String toolSummary;
  final String userMessage;
  final Map<String, dynamic>? toolActivityCard;

  factory _AgentRunSnapshot.fromMap(Map<String, dynamic> map) {
    final activeTool = _asStringMap(map['activeTool']);
    return _AgentRunSnapshot(
      taskId: (map['taskId'] ?? '').toString(),
      conversationId: _asNullableString(map['conversationId']),
      conversationMode: (map['conversationMode'] ?? 'normal').toString(),
      startedAtMillis: _asInt(map['startedAtMillis']) ?? 0,
      elapsedMillis: _asInt(map['elapsedMillis']) ?? 0,
      toolName: (activeTool['toolName'] ?? '').toString(),
      toolSummary: (activeTool['summary'] ?? '').toString(),
      userMessage: (map['userMessage'] ?? '').toString(),
      toolActivityCard: buildAgentToolActivityCardFromActiveRun(map),
    );
  }

  String get title {
    final id = conversationId;
    if (id != null && id.isNotEmpty) {
      return _t('对话 #$id');
    }
    final suffix = shortTaskId;
    return suffix.isEmpty ? _t('Agent 后端任务') : _t('Agent $suffix');
  }

  String get shortTaskId {
    final trimmed = taskId.trim();
    if (trimmed.length <= 8) return trimmed;
    return trimmed.substring(trimmed.length - 8);
  }

  String get toolText {
    final name = toolName.trim();
    final summary = toolSummary.trim();
    if (name.isEmpty && summary.isEmpty) return _t('等待模型响应');
    if (summary.isEmpty) return name;
    if (name.isEmpty) return summary;
    return '$name · $summary';
  }

  String get compactSummary {
    final message = userMessage.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (message.isNotEmpty) return message;
    return toolText;
  }

  String get conversationModeLabel {
    return switch (conversationMode) {
      'chat_only' => _t('纯聊天'),
      'subagent' => _t('子任务'),
      'openclaw' => 'OpenClaw',
      'codex' => 'Codex',
      _ => 'Agent',
    };
  }

  String get elapsedLabel {
    final seconds = (elapsedMillis / 1000).floor();
    if (seconds < 60) return '${seconds}s';
    final minutes = seconds ~/ 60;
    final restSeconds = seconds % 60;
    if (minutes < 60) return '${minutes}m ${restSeconds}s';
    final hours = minutes ~/ 60;
    final restMinutes = minutes % 60;
    return '${hours}h ${restMinutes}m';
  }

  String get elapsedCompactLabel {
    final seconds = (elapsedMillis / 1000).floor();
    if (seconds < 60) return '${seconds}s';
    final minutes = seconds ~/ 60;
    if (minutes < 60) return '${minutes}m';
    final hours = minutes ~/ 60;
    if (hours < 24) return '${hours}h';
    return '${hours ~/ 24}d';
  }

  static int? _asInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw.trim());
    return null;
  }

  static String? _asNullableString(dynamic raw) {
    if (raw == null) return null;
    final value = raw.toString().trim();
    return value.isEmpty || value == '0' ? null : value;
  }

  static Map<String, dynamic> _asStringMap(dynamic raw) {
    if (raw is! Map) return const <String, dynamic>{};
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }
}

extension on Offset {
  Offset clampTo(Rect bounds) {
    return Offset(
      dx.clamp(bounds.left, bounds.right).toDouble(),
      dy.clamp(bounds.top, bounds.bottom).toDouble(),
    );
  }
}
