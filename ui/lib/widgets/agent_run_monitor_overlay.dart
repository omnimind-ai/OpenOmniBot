import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/floating_overlay_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

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
    _latestEvents[event.taskId] = _AgentRunEventSummary.fromEvent(event);
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (!_floatingOverlayEnabled) return;
    if (_refreshing) return;
    _refreshing = true;
    try {
      final rows = await AssistsMessageService.listActiveAgentRuns();
      if (!mounted) return;
      setState(() {
        _runs
          ..clear()
          ..addAll(rows.map(_AgentRunSnapshot.fromMap));
      });
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _showRuns() async {
    await _refresh();
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => _AgentRunMonitorSheet(
        initialRuns: List.of(_runs),
        initialEvents: Map.of(_latestEvents),
        onOpenRun: _openRun,
        onDisableFloatingOverlay: _disableFloatingOverlay,
      ),
    );
    unawaited(_refresh());
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
    context.go(
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
      _showSnack('关闭悬浮球失败');
      return;
    }
    _showSnack('悬浮球已关闭，可在设置里重新开启');
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
    final visibleRows = math.min(_runs.length, 4);
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
    if (_runs.isEmpty) return const SizedBox.shrink();
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
                      ? () => _setCapsuleCollapsed(false)
                      : _showRuns,
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
                    label: _capsuleCollapsed
                        ? '运行中的 Agent：${_runs.length} 个。轻点展开摘要。'
                        : '运行中的 Agent：${_runs.length} 个。轻点打开完整管理面板。',
                    child: SizedBox(
                      width: capsuleSize.width,
                      height: capsuleSize.height,
                      child: Center(
                        child: _capsuleCollapsed
                            ? _AgentRunMiniCapsule(
                                count: _runs.length,
                                dragging: _dragging,
                              )
                            : _AgentRunExpandedCapsule(
                                runs: _runs,
                                latestEvents: _latestEvents,
                                dragging: _dragging,
                                onCollapse: () => _setCapsuleCollapsed(true),
                                onDisable: () =>
                                    unawaited(_disableFloatingOverlay()),
                              ),
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
            _AgentRunStatusDot(color: palette.accentPrimary, size: 6),
            const SizedBox(width: 5),
            Text(
              count > 99 ? '99+' : '$count',
              style: TextStyle(
                color: palette.textPrimary,
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
    required this.onDisable,
  });

  final List<_AgentRunSnapshot> runs;
  final Map<String, _AgentRunEventSummary> latestEvents;
  final bool dragging;
  final VoidCallback onCollapse;
  final VoidCallback onDisable;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return AnimatedScale(
      scale: dragging ? 1.02 : 1,
      duration: const Duration(milliseconds: 140),
      curve: Curves.easeOutCubic,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        curve: Curves.easeOutCubic,
        width: double.infinity,
        height: double.infinity,
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
                    '运行中 ${runs.length} 个 Agent',
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
                Text(
                  '点开',
                  style: TextStyle(
                    color: palette.textTertiary,
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Tooltip(
                  message: '隐藏悬浮球',
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
                Tooltip(
                  message: '收起',
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
              child: ListView.separated(
                padding: EdgeInsets.zero,
                itemCount: runs.length,
                separatorBuilder: (_, __) => const SizedBox(height: 5),
                itemBuilder: (context, index) {
                  final run = runs[index];
                  return _AgentRunCompactRow(
                    run: run,
                    latestEvent: latestEvents[run.taskId],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentRunCompactRow extends StatelessWidget {
  const _AgentRunCompactRow({required this.run, required this.latestEvent});

  final _AgentRunSnapshot run;
  final _AgentRunEventSummary? latestEvent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final summary = latestEvent?.label ?? run.compactSummary;
    return Container(
      height: 37,
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.78),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: latestEvent?.isError == true
                  ? const Color(0xFFB25518)
                  : palette.accentPrimary,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  run.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 10.5,
                    fontWeight: FontWeight.w900,
                    height: 1,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  summary,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 9.5,
                    fontWeight: FontWeight.w700,
                    height: 1,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Text(
            run.elapsedLabel,
            style: TextStyle(
              color: palette.textTertiary,
              fontSize: 9,
              fontWeight: FontWeight.w800,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

class _AgentRunMonitorSheet extends StatefulWidget {
  const _AgentRunMonitorSheet({
    required this.initialRuns,
    required this.initialEvents,
    required this.onOpenRun,
    required this.onDisableFloatingOverlay,
  });

  final List<_AgentRunSnapshot> initialRuns;
  final Map<String, _AgentRunEventSummary> initialEvents;
  final ValueChanged<_AgentRunSnapshot> onOpenRun;
  final VoidCallback onDisableFloatingOverlay;

  @override
  State<_AgentRunMonitorSheet> createState() => _AgentRunMonitorSheetState();
}

class _AgentRunMonitorSheetState extends State<_AgentRunMonitorSheet> {
  late List<_AgentRunSnapshot> _runs = widget.initialRuns;
  late final Map<String, _AgentRunEventSummary> _latestEvents = Map.of(
    widget.initialEvents,
  );
  StreamSubscription<Map<String, dynamic>>? _stateSub;
  bool _loading = false;
  bool _cancellingAll = false;
  final Set<String> _cancellingTaskIds = <String>{};

  @override
  void initState() {
    super.initState();
    _stateSub = AssistsMessageService.agentRunStateChangedStream.listen(
      (_) => unawaited(_refresh()),
    );
    AssistsMessageService.setOnAgentStreamEventCallback(_handleAgentEvent);
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _stateSub?.cancel();
    AssistsMessageService.removeOnAgentStreamEventCallback(_handleAgentEvent);
    super.dispose();
  }

  void _handleAgentEvent(AgentStreamEvent event) {
    if (!mounted) return;
    setState(() {
      _latestEvents[event.taskId] = _AgentRunEventSummary.fromEvent(event);
    });
  }

  Future<void> _refresh() async {
    if (mounted) {
      setState(() => _loading = true);
    }
    final rows = await AssistsMessageService.listActiveAgentRuns();
    if (!mounted) return;
    setState(() {
      _runs = rows.map(_AgentRunSnapshot.fromMap).toList(growable: false);
      _loading = false;
    });
  }

  Future<void> _cancelRun(_AgentRunSnapshot run) async {
    if (_cancellingTaskIds.contains(run.taskId)) return;
    setState(() => _cancellingTaskIds.add(run.taskId));
    final success = await AssistsMessageService.cancelRunningTask(
      taskId: run.taskId,
    );
    await Future<void>.delayed(const Duration(milliseconds: 250));
    if (!mounted) return;
    setState(() => _cancellingTaskIds.remove(run.taskId));
    if (!success) {
      _showSnack('停止失败');
    }
    await _refresh();
  }

  Future<void> _cancelAll() async {
    if (_cancellingAll) return;
    setState(() => _cancellingAll = true);
    final success = await AssistsMessageService.cancelRunningTask();
    await Future<void>.delayed(const Duration(milliseconds: 250));
    if (!mounted) return;
    setState(() => _cancellingAll = false);
    if (!success) {
      _showSnack('停止失败');
    }
    await _refresh();
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

  void _openRun(_AgentRunSnapshot run) {
    Navigator.of(context).pop();
    widget.onOpenRun(run);
  }

  void _disableFloatingOverlay() {
    Navigator.of(context).pop();
    widget.onDisableFloatingOverlay();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final maxHeight = MediaQuery.sizeOf(context).height * 0.72;
    return SafeArea(
      top: false,
      child: Container(
        constraints: BoxConstraints(maxHeight: maxHeight),
        decoration: BoxDecoration(
          color: palette.surfacePrimary,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
          border: Border(top: BorderSide(color: palette.borderSubtle)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: palette.borderStrong,
                borderRadius: BorderRadius.circular(999),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 10, 10),
              child: Row(
                children: [
                  Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: palette.accentPrimary.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(
                      Icons.hub_outlined,
                      color: palette.accentPrimary,
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '运行中的 Agent',
                          style: TextStyle(
                            color: palette.textPrimary,
                            fontSize: 16,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                        Text(
                          _runs.isEmpty
                              ? '当前没有后端任务'
                              : '${_runs.length} 个后端任务正在执行',
                          style: TextStyle(
                            color: palette.textTertiary,
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: '刷新',
                    onPressed: _loading ? null : () => unawaited(_refresh()),
                    icon: _loading
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.refresh_rounded),
                  ),
                  IconButton(
                    tooltip: '隐藏悬浮球',
                    onPressed: _disableFloatingOverlay,
                    icon: const Icon(Icons.visibility_off_outlined),
                  ),
                  IconButton.filledTonal(
                    tooltip: '停止全部',
                    onPressed: _runs.isEmpty || _cancellingAll
                        ? null
                        : () => unawaited(_cancelAll()),
                    icon: _cancellingAll
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.stop_circle_outlined, size: 20),
                  ),
                ],
              ),
            ),
            Divider(height: 1, color: palette.borderSubtle),
            Flexible(
              child: _runs.isEmpty
                  ? Center(
                      child: Padding(
                        padding: const EdgeInsets.all(28),
                        child: Text(
                          '没有正在执行的 Agent 后端任务',
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    )
                  : ListView.separated(
                      shrinkWrap: true,
                      padding: const EdgeInsets.fromLTRB(12, 10, 12, 16),
                      itemCount: _runs.length,
                      separatorBuilder: (_, __) => const SizedBox(height: 8),
                      itemBuilder: (context, index) {
                        final run = _runs[index];
                        final cancelling = _cancellingTaskIds.contains(
                          run.taskId,
                        );
                        return _AgentRunTile(
                          run: run,
                          latestEvent: _latestEvents[run.taskId],
                          cancelling: cancelling,
                          onOpen: run.conversationId == null
                              ? null
                              : () => _openRun(run),
                          onCancel: () => unawaited(_cancelRun(run)),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentRunTile extends StatelessWidget {
  const _AgentRunTile({
    required this.run,
    required this.latestEvent,
    required this.cancelling,
    required this.onOpen,
    required this.onCancel,
  });

  final _AgentRunSnapshot run;
  final _AgentRunEventSummary? latestEvent;
  final bool cancelling;
  final VoidCallback? onOpen;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final accent = palette.accentPrimary;
    final toolText = run.toolText;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onOpen,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: palette.surfaceSecondary.withValues(alpha: 0.78),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  _AgentRunPulse(color: accent, size: 18),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      run.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  _AgentRunChip(label: run.conversationModeLabel),
                  const SizedBox(width: 6),
                  _AgentRunChip(label: run.elapsedLabel),
                ],
              ),
              const SizedBox(height: 10),
              Text(
                toolText,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  height: 1.25,
                ),
              ),
              if (latestEvent != null) ...[
                const SizedBox(height: 8),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: palette.surfacePrimary.withValues(alpha: 0.72),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: palette.borderSubtle),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        latestEvent!.icon,
                        size: 15,
                        color: latestEvent!.isError
                            ? const Color(0xFFB25518)
                            : accent,
                      ),
                      const SizedBox(width: 7),
                      Expanded(
                        child: Text(
                          latestEvent!.label,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: latestEvent!.isError
                                ? const Color(0xFFB25518)
                                : palette.textSecondary,
                            fontSize: 11,
                            fontWeight: FontWeight.w800,
                            height: 1.25,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      run.taskId,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textTertiary,
                        fontSize: 10,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  if (onOpen != null) ...[
                    TextButton.icon(
                      onPressed: onOpen,
                      icon: const Icon(Icons.open_in_new_rounded, size: 16),
                      label: const Text('打开'),
                      style: TextButton.styleFrom(
                        visualDensity: VisualDensity.compact,
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                      ),
                    ),
                    const SizedBox(width: 4),
                  ],
                  IconButton.filledTonal(
                    tooltip: '停止这个 Agent',
                    onPressed: cancelling ? null : onCancel,
                    icon: cancelling
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.stop_rounded, size: 18),
                  ),
                ],
              ),
            ],
          ),
        ),
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

class _AgentRunChip extends StatelessWidget {
  const _AgentRunChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: palette.surfacePrimary.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: 10,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class _AgentRunEventSummary {
  const _AgentRunEventSummary({
    required this.label,
    required this.icon,
    this.isError = false,
  });

  final String label;
  final IconData icon;
  final bool isError;

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
    return switch (event.kind) {
      AgentStreamEventKind.thinkingStarted => const _AgentRunEventSummary(
        label: '正在思考',
        icon: Icons.psychology_alt_outlined,
      ),
      AgentStreamEventKind.thinkingSnapshot => _AgentRunEventSummary(
        label: message.isEmpty ? '正在整理方案' : message,
        icon: Icons.psychology_alt_outlined,
      ),
      AgentStreamEventKind.textSnapshot => _AgentRunEventSummary(
        label: message.isEmpty ? '正在输出' : message,
        icon: Icons.notes_rounded,
      ),
      AgentStreamEventKind.toolStarted => _AgentRunEventSummary(
        label: toolName.isEmpty ? '开始调用工具' : '调用 $toolName',
        icon: Icons.build_circle_outlined,
      ),
      AgentStreamEventKind.toolProgress => _AgentRunEventSummary(
        label: message.isEmpty
            ? (toolName.isEmpty ? '工具执行中' : '$toolName 执行中')
            : message,
        icon: Icons.sync_rounded,
      ),
      AgentStreamEventKind.toolCompleted => _AgentRunEventSummary(
        label: toolName.isEmpty ? '工具完成' : '$toolName 完成',
        icon: Icons.check_circle_outline_rounded,
      ),
      AgentStreamEventKind.permissionRequired => const _AgentRunEventSummary(
        label: '等待权限确认',
        icon: Icons.verified_user_outlined,
      ),
      AgentStreamEventKind.clarifyRequired => _AgentRunEventSummary(
        label: event.question.isEmpty ? '等待补充信息' : event.question,
        icon: Icons.help_outline_rounded,
      ),
      AgentStreamEventKind.error => _AgentRunEventSummary(
        label: message.isEmpty ? '运行出错' : message,
        icon: Icons.error_outline_rounded,
        isError: true,
      ),
      AgentStreamEventKind.completed => const _AgentRunEventSummary(
        label: '即将完成',
        icon: Icons.done_all_rounded,
      ),
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
  });

  final String taskId;
  final String? conversationId;
  final String conversationMode;
  final int startedAtMillis;
  final int elapsedMillis;
  final String toolName;
  final String toolSummary;
  final String userMessage;

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
    );
  }

  String get title {
    final id = conversationId;
    if (id != null && id.isNotEmpty) {
      return '对话 #$id';
    }
    final suffix = shortTaskId;
    return suffix.isEmpty ? 'Agent 后端任务' : 'Agent $suffix';
  }

  String get shortTaskId {
    final trimmed = taskId.trim();
    if (trimmed.length <= 8) return trimmed;
    return trimmed.substring(trimmed.length - 8);
  }

  String get toolText {
    final name = toolName.trim();
    final summary = toolSummary.trim();
    if (name.isEmpty && summary.isEmpty) return '等待模型响应';
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
      'chat_only' => '纯聊天',
      'subagent' => '子任务',
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
