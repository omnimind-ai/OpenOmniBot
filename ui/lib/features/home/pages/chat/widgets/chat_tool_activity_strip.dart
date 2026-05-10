import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart'
    hide buildAgentToolTranscript;
import 'package:ui/features/home/pages/command_overlay/services/tool_card_detail_gesture_gate.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

const ValueKey<String> kChatToolActivityBarKey = ValueKey<String>(
  'chat-tool-activity-bar',
);
const ValueKey<String> kChatToolActivityPanelKey = ValueKey<String>(
  'chat-tool-activity-panel',
);
const ValueKey<String> kChatToolActivityPreviewKey = ValueKey<String>(
  'chat-tool-activity-preview',
);
const ValueKey<String> kChatToolActivityToggleKey = ValueKey<String>(
  'chat-tool-activity-toggle',
);
const ValueKey<String> kChatToolActivityStopKey = ValueKey<String>(
  'chat-tool-activity-stop',
);

const double _kToolActivityRowHeight = 32;
const double _kToolActivitySurfaceRadius = 18;
const double _kToolActivityPreviewWidth = 94;
const double _kToolActivityPreviewHeight = 54;
const double _kToolActivityPreviewOverlap = 30;
const double _kToolActivitySurfaceHorizontalInset = 20;
const double _kToolActivityDrawerMaxHeight = 264;
const double _kToolActivityTypeSlotWidth = 34;
const double _kToolActivityStatusSlotWidth = 42;
const double _kToolActivityTrailingSlotWidth = 24;
const double _kToolActivityAttachedBorderReveal = 1.5;
const Color _kToolActivitySurfaceColor = Color(0xFFF9FCFF);
const BorderRadius _kToolActivitySurfaceBorderRadius = BorderRadius.only(
  topLeft: Radius.circular(_kToolActivitySurfaceRadius),
  topRight: Radius.circular(_kToolActivitySurfaceRadius),
);
const BorderRadius _kToolActivityPreviewBorderRadius = BorderRadius.all(
  Radius.circular(18),
);

class ChatToolActivityStrip extends StatefulWidget {
  const ChatToolActivityStrip({
    super.key,
    required this.messages,
    this.anchorRect,
    this.onOccupiedHeightChanged,
    this.expanded,
    this.onExpandedChanged,
    this.suppressSurfaceShadow = false,
    this.onStopToolCall,
    this.runningOnly = false,
    this.showPreviewThumbnail = true,
    this.openActiveCardOnTap = false,
  });

  final List<ChatMessageModel> messages;
  final Rect? anchorRect;
  final ValueChanged<double>? onOccupiedHeightChanged;
  final bool? expanded;
  final ValueChanged<bool>? onExpandedChanged;
  final bool suppressSurfaceShadow;
  final Future<bool> Function(String taskId, String cardId)? onStopToolCall;
  final bool runningOnly;
  final bool showPreviewThumbnail;
  final bool openActiveCardOnTap;

  @override
  State<ChatToolActivityStrip> createState() => _ChatToolActivityStripState();
}

class _ChatToolActivityStripState extends State<ChatToolActivityStrip> {
  bool _expanded = false;
  double? _lastReportedOccupiedHeight;
  final Set<int> _heldPointerIds = <int>{};
  String? _pendingStopCardId;

  bool get _resolvedExpanded => widget.expanded ?? _expanded;

  List<Map<String, dynamic>> _resolvedCards() {
    return widget.runningOnly
        ? extractRunningAgentToolCards(widget.messages)
        : extractAgentToolCards(widget.messages);
  }

  @override
  Widget build(BuildContext context) {
    final cards = _resolvedCards();
    final activeCard = resolveActiveAgentToolCard(cards);
    if (activeCard == null) {
      _scheduleExpandedResetIfNeeded();
      _reportOccupiedHeight(0);
      return const SizedBox.shrink();
    }

    final activeCardId = _cardIdentity(activeCard);
    _schedulePendingStopResetIfNeeded(activeCardId: activeCardId);
    final historyCards = cards
        .where((card) => _cardIdentity(card) != activeCardId)
        .toList(growable: false);
    final canExpand = historyCards.isNotEmpty;
    final isExpanded = _resolvedExpanded && canExpand;
    final previewVisible = widget.showPreviewThumbnail && !isExpanded;
    final activeTranscript = previewVisible
        ? buildAgentToolTranscript(activeCard)
        : null;
    if (!canExpand && _resolvedExpanded) {
      _scheduleExpandedResetIfNeeded();
    }
    final historyHeight = isExpanded
        ? _resolveHistoryHeight(historyCards)
        : 0.0;
    final dividerHeight = isExpanded ? 1.0 : 0.0;
    final surfaceHeight =
        _kToolActivityRowHeight + historyHeight + dividerHeight;
    final collapsedOccupiedHeight =
        _kToolActivityRowHeight +
        (widget.showPreviewThumbnail
            ? _kToolActivityPreviewHeight - _kToolActivityPreviewOverlap
            : 0.0);
    final totalHeight =
        surfaceHeight +
        (previewVisible
            ? _kToolActivityPreviewHeight - _kToolActivityPreviewOverlap
            : 0.0);
    final collapsedLeadingInset = math.max(
      0.0,
      _kToolActivityPreviewWidth - _kToolActivitySurfaceHorizontalInset + 2,
    );
    _reportOccupiedHeight(collapsedOccupiedHeight);

    return AnimatedSize(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutQuart,
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        width: widget.anchorRect?.width ?? double.infinity,
        height: totalHeight,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: _kToolActivitySurfaceHorizontalInset,
              right: _kToolActivitySurfaceHorizontalInset,
              bottom: 0,
              child: _ActivityDrawerSurface(
                activeCard: activeCard,
                historyCards: historyCards,
                historyHeight: historyHeight,
                expanded: isExpanded,
                canExpand: canExpand,
                suppressShadow: widget.suppressSurfaceShadow,
                leadingInset: previewVisible ? collapsedLeadingInset : 0,
                showPreviewCutout: previewVisible,
                openActiveCardOnTap: widget.openActiveCardOnTap,
                onToggle: () => _handleExpandedChanged(!isExpanded),
                onStopToolCall: widget.onStopToolCall == null
                    ? null
                    : () => _handleStopToolCall(activeCard),
                isStopPending: _pendingStopCardId == activeCardId,
                onOpenCard: (cardData) =>
                    _openCardDetailSheet(context, cardData: cardData),
                onHistoryPointerDown: _handleHistoryPointerDown,
                onHistoryPointerEnd: _handleHistoryPointerEnd,
              ),
            ),
            if (widget.showPreviewThumbnail)
              Positioned(
                left: 0,
                top: 0,
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 220),
                  switchInCurve: Curves.easeOutCubic,
                  switchOutCurve: Curves.easeInCubic,
                  transitionBuilder: (child, animation) {
                    final offset = Tween<Offset>(
                      begin: const Offset(-0.05, 0.12),
                      end: Offset.zero,
                    ).animate(animation);
                    return FadeTransition(
                      opacity: animation,
                      child: SlideTransition(position: offset, child: child),
                    );
                  },
                  child: previewVisible && activeTranscript != null
                      ? _TerminalThumbnail(
                          key: kChatToolActivityPreviewKey,
                          transcript: activeTranscript,
                          onTap: () => _openCardDetailSheet(
                            context,
                            cardData: activeCard,
                          ),
                        )
                      : const SizedBox.shrink(key: ValueKey('hidden-preview')),
                ),
              ),
          ],
        ),
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ChatToolActivityStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    _schedulePendingStopResetIfNeeded();
    if (widget.messages.isEmpty && _lastReportedOccupiedHeight != 0) {
      _reportOccupiedHeight(0);
    }
    if (oldWidget.expanded == true && widget.expanded != true) {
      _releaseHeldPointers();
    }
  }

  @override
  void dispose() {
    _releaseHeldPointers();
    super.dispose();
  }

  String _cardIdentity(Map<String, dynamic> cardData) {
    final explicit = (cardData['cardId'] ?? '').toString().trim();
    if (explicit.isNotEmpty) {
      return explicit;
    }
    return [
      (cardData['taskId'] ?? '').toString(),
      (cardData['toolName'] ?? '').toString(),
      (cardData['toolTitle'] ?? '').toString(),
      (cardData['status'] ?? '').toString(),
    ].join('|');
  }

  double _resolveHistoryHeight(List<Map<String, dynamic>> cards) {
    final visibleCount = cards.length.clamp(1, 5);
    final estimated = visibleCount * _kToolActivityRowHeight;
    return math.min(_kToolActivityDrawerMaxHeight, estimated.toDouble());
  }

  void _handleExpandedChanged(bool expanded) {
    if (_resolvedExpanded == expanded && widget.expanded != null) {
      return;
    }
    if (widget.expanded == null) {
      if (_expanded == expanded) {
        return;
      }
      setState(() {
        _expanded = expanded;
      });
    }
    if (!expanded) {
      _releaseHeldPointers();
    }
    widget.onExpandedChanged?.call(expanded);
  }

  void _scheduleExpandedResetIfNeeded() {
    if (!_resolvedExpanded) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_resolvedExpanded) {
        return;
      }
      _handleExpandedChanged(false);
    });
  }

  void _schedulePendingStopResetIfNeeded({String? activeCardId}) {
    final pendingCardId = _pendingStopCardId;
    if (pendingCardId == null) {
      return;
    }
    final cards = _resolvedCards();
    Map<String, dynamic>? pendingCard;
    for (final card in cards) {
      if (_cardIdentity(card) == pendingCardId) {
        pendingCard = card;
        break;
      }
    }
    final resolvedActiveCard = resolveActiveAgentToolCard(cards);
    final normalizedActiveCardId =
        activeCardId ??
        (resolvedActiveCard == null ? null : _cardIdentity(resolvedActiveCard));
    final stillPending =
        pendingCard != null &&
        (pendingCard['status'] ?? '').toString() == 'running' &&
        normalizedActiveCardId == pendingCardId;
    if (stillPending) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _pendingStopCardId != pendingCardId) {
        return;
      }
      setState(() {
        _pendingStopCardId = null;
      });
    });
  }

  Future<void> _handleStopToolCall(Map<String, dynamic> cardData) async {
    final onStopToolCall = widget.onStopToolCall;
    if (onStopToolCall == null) {
      return;
    }
    final taskId = (cardData['taskId'] ?? '').toString().trim();
    final cardId = _cardIdentity(cardData);
    if (taskId.isEmpty || cardId.isEmpty || _pendingStopCardId == cardId) {
      return;
    }
    setState(() {
      _pendingStopCardId = cardId;
    });

    var success = false;
    try {
      success = await onStopToolCall(taskId, cardId);
    } catch (_) {
      success = false;
    }
    if (!mounted) {
      return;
    }
    if (success) {
      return;
    }
    setState(() {
      if (_pendingStopCardId == cardId) {
        _pendingStopCardId = null;
      }
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(LegacyTextLocalizer.localize('停止工具调用失败，请稍后重试'))),
    );
  }

  void _handleHistoryPointerDown(int pointer) {
    if (_heldPointerIds.add(pointer)) {
      ToolCardDetailGestureGate.holdPointer(pointer);
    }
  }

  void _handleHistoryPointerEnd(int pointer) {
    if (_heldPointerIds.remove(pointer)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
  }

  void _releaseHeldPointers() {
    if (_heldPointerIds.isEmpty) {
      return;
    }
    for (final pointer in _heldPointerIds.toList(growable: false)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
    _heldPointerIds.clear();
  }

  void _reportOccupiedHeight(double height) {
    if (widget.onOccupiedHeightChanged == null) {
      return;
    }
    final normalized = height.isFinite ? height : 0.0;
    if (_lastReportedOccupiedHeight != null &&
        (_lastReportedOccupiedHeight! - normalized).abs() < 0.5) {
      return;
    }
    _lastReportedOccupiedHeight = normalized;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      widget.onOccupiedHeightChanged?.call(normalized);
    });
  }

  Future<void> _openCardDetailSheet(
    BuildContext context, {
    required Map<String, dynamic> cardData,
  }) {
    return showAgentToolDetailSheet(context, cardData: cardData);
  }
}

class ChatCommandActivityStrip extends StatefulWidget {
  const ChatCommandActivityStrip({
    super.key,
    required this.commands,
    required this.onSelectCommand,
    this.anchorRect,
    this.onOccupiedHeightChanged,
    this.suppressSurfaceShadow = false,
  });

  final List<Map<String, dynamic>> commands;
  final ValueChanged<Map<String, dynamic>> onSelectCommand;
  final Rect? anchorRect;
  final ValueChanged<double>? onOccupiedHeightChanged;
  final bool suppressSurfaceShadow;

  @override
  State<ChatCommandActivityStrip> createState() =>
      _ChatCommandActivityStripState();
}

class _ChatCommandActivityStripState extends State<ChatCommandActivityStrip> {
  double? _lastReportedOccupiedHeight;
  final Set<int> _heldPointerIds = <int>{};

  @override
  Widget build(BuildContext context) {
    final commands = widget.commands;
    if (commands.isEmpty) {
      _reportOccupiedHeight(0);
      return const SizedBox.shrink();
    }

    final activeCard = commands.first;
    final historyCards = commands.length > 1
        ? commands.sublist(1)
        : const <Map<String, dynamic>>[];
    final showHistory = historyCards.isNotEmpty;
    final historyHeight = showHistory
        ? _resolveHistoryHeight(historyCards)
        : 0.0;
    final dividerHeight = showHistory ? 1.0 : 0.0;
    final surfaceHeight =
        _kToolActivityRowHeight + historyHeight + dividerHeight;
    _reportOccupiedHeight(surfaceHeight);

    return AnimatedSize(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutQuart,
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        width: widget.anchorRect?.width ?? double.infinity,
        height: surfaceHeight,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: _kToolActivitySurfaceHorizontalInset,
              right: _kToolActivitySurfaceHorizontalInset,
              bottom: 0,
              child: _CommandDrawerSurface(
                activeCard: activeCard,
                historyCards: historyCards,
                historyHeight: historyHeight,
                expanded: showHistory,
                canExpand: false,
                suppressShadow: widget.suppressSurfaceShadow,
                leadingInset: 0,
                onToggle: () {},
                onSelectCommand: widget.onSelectCommand,
                onHistoryPointerDown: _handleHistoryPointerDown,
                onHistoryPointerEnd: _handleHistoryPointerEnd,
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ChatCommandActivityStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.commands.isEmpty && _lastReportedOccupiedHeight != 0) {
      _reportOccupiedHeight(0);
    }
  }

  @override
  void dispose() {
    _releaseHeldPointers();
    super.dispose();
  }

  double _resolveHistoryHeight(List<Map<String, dynamic>> cards) {
    final visibleCount = cards.length.clamp(1, 5);
    final estimated = visibleCount * _kToolActivityRowHeight;
    return math.min(_kToolActivityDrawerMaxHeight, estimated.toDouble());
  }

  void _handleHistoryPointerDown(int pointer) {
    if (_heldPointerIds.add(pointer)) {
      ToolCardDetailGestureGate.holdPointer(pointer);
    }
  }

  void _handleHistoryPointerEnd(int pointer) {
    if (_heldPointerIds.remove(pointer)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
  }

  void _releaseHeldPointers() {
    if (_heldPointerIds.isEmpty) {
      return;
    }
    for (final pointer in _heldPointerIds.toList(growable: false)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
    _heldPointerIds.clear();
  }

  void _reportOccupiedHeight(double height) {
    if (widget.onOccupiedHeightChanged == null) {
      return;
    }
    final normalized = height.isFinite ? height : 0.0;
    if (_lastReportedOccupiedHeight != null &&
        (_lastReportedOccupiedHeight! - normalized).abs() < 0.5) {
      return;
    }
    _lastReportedOccupiedHeight = normalized;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      widget.onOccupiedHeightChanged?.call(normalized);
    });
  }
}

class _CommandDrawerSurface extends StatelessWidget {
  const _CommandDrawerSurface({
    required this.activeCard,
    required this.historyCards,
    required this.historyHeight,
    required this.expanded,
    required this.canExpand,
    required this.suppressShadow,
    required this.leadingInset,
    required this.onToggle,
    required this.onSelectCommand,
    required this.onHistoryPointerDown,
    required this.onHistoryPointerEnd,
  });

  final Map<String, dynamic> activeCard;
  final List<Map<String, dynamic>> historyCards;
  final double historyHeight;
  final bool expanded;
  final bool canExpand;
  final bool suppressShadow;
  final double leadingInset;
  final VoidCallback onToggle;
  final ValueChanged<Map<String, dynamic>> onSelectCommand;
  final ValueChanged<int> onHistoryPointerDown;
  final ValueChanged<int> onHistoryPointerEnd;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final surfaceColor = context.isDarkTheme
        ? palette.surfacePrimary
        : _kToolActivitySurfaceColor;
    final dividerColor = context.isDarkTheme
        ? palette.borderSubtle.withValues(alpha: 0.52)
        : const Color(0x140F2034);
    final bottomReveal = suppressShadow
        ? _kToolActivityAttachedBorderReveal
        : 0.0;
    return PhysicalShape(
      key: const ValueKey('chat-command-activity-bar'),
      color: surfaceColor,
      shadowColor: suppressShadow
          ? Colors.transparent
          : context.isDarkTheme
          ? palette.shadowColor.withValues(alpha: 0.42)
          : const Color(0x18111B2D),
      elevation: suppressShadow ? 0 : (expanded ? 8 : 6),
      clipBehavior: Clip.antiAlias,
      clipper: _ActivityDrawerClipper(
        showPreviewCutout: false,
        bottomReveal: bottomReveal,
      ),
      child: ColoredBox(
        color: surfaceColor,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedCrossFade(
              duration: const Duration(milliseconds: 240),
              firstCurve: Curves.easeInCubic,
              secondCurve: Curves.easeOutCubic,
              sizeCurve: Curves.easeOutQuart,
              alignment: Alignment.bottomCenter,
              crossFadeState: expanded
                  ? CrossFadeState.showSecond
                  : CrossFadeState.showFirst,
              firstChild: const SizedBox.shrink(
                key: ValueKey('collapsed-command-panel'),
              ),
              secondChild: SizedBox(
                height: historyHeight,
                child: _HistoryDrawer(
                  cards: historyCards,
                  onOpenCard: onSelectCommand,
                  onPointerDown: onHistoryPointerDown,
                  onPointerEnd: onHistoryPointerEnd,
                ),
              ),
            ),
            AnimatedContainer(
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOutCubic,
              height: expanded ? 1 : 0,
              margin: const EdgeInsets.only(left: 18, right: 10),
              color: dividerColor,
            ),
            ToolActivityRow(
              card: activeCard,
              leadingInset: leadingInset,
              onTap: () => onSelectCommand(activeCard),
              trailing: canExpand
                  ? _ActivityBarTrailing(expanded: expanded, onToggle: onToggle)
                  : null,
            ),
          ],
        ),
      ),
    );
  }
}

class _ActivityDrawerSurface extends StatelessWidget {
  const _ActivityDrawerSurface({
    required this.activeCard,
    required this.historyCards,
    required this.historyHeight,
    required this.expanded,
    required this.canExpand,
    required this.suppressShadow,
    required this.leadingInset,
    required this.showPreviewCutout,
    required this.openActiveCardOnTap,
    required this.onToggle,
    required this.isStopPending,
    required this.onStopToolCall,
    required this.onOpenCard,
    required this.onHistoryPointerDown,
    required this.onHistoryPointerEnd,
  });

  final Map<String, dynamic> activeCard;
  final List<Map<String, dynamic>> historyCards;
  final double historyHeight;
  final bool expanded;
  final bool canExpand;
  final bool suppressShadow;
  final double leadingInset;
  final bool showPreviewCutout;
  final bool openActiveCardOnTap;
  final VoidCallback onToggle;
  final bool isStopPending;
  final VoidCallback? onStopToolCall;
  final ValueChanged<Map<String, dynamic>> onOpenCard;
  final ValueChanged<int> onHistoryPointerDown;
  final ValueChanged<int> onHistoryPointerEnd;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final surfaceColor = context.isDarkTheme
        ? palette.surfacePrimary
        : _kToolActivitySurfaceColor;
    final dividerColor = context.isDarkTheme
        ? palette.borderSubtle.withValues(alpha: 0.52)
        : const Color(0x140F2034);
    final bottomReveal = suppressShadow
        ? _kToolActivityAttachedBorderReveal
        : 0.0;
    return PhysicalShape(
      key: kChatToolActivityBarKey,
      color: surfaceColor,
      shadowColor: suppressShadow
          ? Colors.transparent
          : context.isDarkTheme
          ? palette.shadowColor.withValues(alpha: 0.42)
          : const Color(0x18111B2D),
      elevation: suppressShadow ? 0 : (expanded ? 8 : 6),
      clipBehavior: Clip.antiAlias,
      clipper: _ActivityDrawerClipper(
        showPreviewCutout: showPreviewCutout,
        bottomReveal: bottomReveal,
      ),
      child: ColoredBox(
        color: surfaceColor,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedCrossFade(
              duration: const Duration(milliseconds: 240),
              firstCurve: Curves.easeInCubic,
              secondCurve: Curves.easeOutCubic,
              sizeCurve: Curves.easeOutQuart,
              alignment: Alignment.bottomCenter,
              crossFadeState: expanded
                  ? CrossFadeState.showSecond
                  : CrossFadeState.showFirst,
              firstChild: const SizedBox.shrink(
                key: ValueKey('collapsed-panel'),
              ),
              secondChild: SizedBox(
                height: historyHeight,
                child: _HistoryDrawer(
                  cards: historyCards,
                  onOpenCard: onOpenCard,
                  onPointerDown: onHistoryPointerDown,
                  onPointerEnd: onHistoryPointerEnd,
                ),
              ),
            ),
            AnimatedContainer(
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOutCubic,
              height: expanded ? 1 : 0,
              margin: const EdgeInsets.only(left: 18, right: 10),
              color: dividerColor,
            ),
            ToolActivityRow(
              card: activeCard,
              leadingInset: leadingInset,
              onTap: openActiveCardOnTap
                  ? () => onOpenCard(activeCard)
                  : (canExpand ? onToggle : null),
              trailing: _supportsToolStop(activeCard) && onStopToolCall != null
                  ? _ToolStopButton(
                      enabled: !isStopPending,
                      onTap: onStopToolCall,
                    )
                  : canExpand
                  ? _ActivityBarTrailing(expanded: expanded, onToggle: onToggle)
                  : null,
            ),
          ],
        ),
      ),
    );
  }

  bool _supportsToolStop(Map<String, dynamic> cardData) {
    return (cardData['status'] ?? '').toString() == 'running';
  }
}

class _ActivityBarTrailing extends StatelessWidget {
  const _ActivityBarTrailing({required this.expanded, required this.onToggle});

  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final color = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : const Color(0xFF657891);
    return GestureDetector(
      key: kChatToolActivityToggleKey,
      behavior: HitTestBehavior.opaque,
      onTap: onToggle,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 1),
        child: AnimatedRotation(
          turns: expanded ? 0 : 0.5,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          child: Icon(Icons.keyboard_arrow_up_rounded, size: 14, color: color),
        ),
      ),
    );
  }
}

class _ToolStopButton extends StatelessWidget {
  const _ToolStopButton({required this.enabled, required this.onTap});

  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final baseColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF657891);
    final foregroundColor = enabled
        ? baseColor
        : baseColor.withValues(alpha: 0.42);
    final borderColor = enabled
        ? foregroundColor.withValues(alpha: 0.48)
        : foregroundColor.withValues(alpha: 0.3);
    final backgroundColor = context.isDarkTheme
        ? palette.surfaceElevated.withValues(alpha: enabled ? 0.88 : 0.72)
        : Colors.white.withValues(alpha: enabled ? 0.9 : 0.72);

    return Tooltip(
      message: LegacyTextLocalizer.localize(enabled ? '停止工具' : '正在停止工具'),
      child: GestureDetector(
        key: kChatToolActivityStopKey,
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? onTap : null,
        child: SizedBox(
          width: _kToolActivityTrailingSlotWidth,
          height: _kToolActivityRowHeight,
          child: Center(
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: 18,
              height: 18,
              decoration: BoxDecoration(
                color: backgroundColor,
                shape: BoxShape.circle,
                border: Border.all(color: borderColor, width: 1),
              ),
              alignment: Alignment.center,
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                width: 6.5,
                height: 6.5,
                decoration: BoxDecoration(
                  color: foregroundColor,
                  borderRadius: BorderRadius.circular(1.8),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _HistoryDrawer extends StatelessWidget {
  const _HistoryDrawer({
    required this.cards,
    required this.onOpenCard,
    required this.onPointerDown,
    required this.onPointerEnd,
  });

  final List<Map<String, dynamic>> cards;
  final ValueChanged<Map<String, dynamic>> onOpenCard;
  final ValueChanged<int> onPointerDown;
  final ValueChanged<int> onPointerEnd;

  @override
  Widget build(BuildContext context) {
    final dividerColor = context.isDarkTheme
        ? context.omniPalette.borderSubtle.withValues(alpha: 0.52)
        : const Color(0x140F2034);
    final scrollable = cards.length > 4;
    return Container(
      key: kChatToolActivityPanelKey,
      padding: EdgeInsets.zero,
      child: Listener(
        behavior: HitTestBehavior.translucent,
        onPointerDown: (event) => onPointerDown(event.pointer),
        onPointerUp: (event) => onPointerEnd(event.pointer),
        onPointerCancel: (event) => onPointerEnd(event.pointer),
        child: ListView.separated(
          reverse: true,
          padding: EdgeInsets.zero,
          shrinkWrap: true,
          physics: scrollable
              ? const BouncingScrollPhysics(parent: ClampingScrollPhysics())
              : const NeverScrollableScrollPhysics(),
          itemBuilder: (context, index) {
            final card = cards[index];
            final isBottomMost = index == 0;
            return DecoratedBox(
              decoration: BoxDecoration(
                border: isBottomMost
                    ? null
                    : Border(bottom: BorderSide(color: dividerColor, width: 1)),
              ),
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: () => onOpenCard(card),
                  child: ToolActivityRow(card: card),
                ),
              ),
            );
          },
          separatorBuilder: (_, __) => const SizedBox.shrink(),
          itemCount: cards.length,
        ),
      ),
    );
  }
}

class ToolActivityRow extends StatelessWidget {
  const ToolActivityRow({
    super.key,
    required this.card,
    this.leadingInset = 0,
    this.onTap,
    this.trailing,
  });

  final Map<String, dynamic> card;
  final double leadingInset;
  final VoidCallback? onTap;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final primaryTextColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final secondaryTextColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF7C8DA5);
    final status = (card['status'] ?? 'running').toString();
    final toolTypeLabel = resolveAgentToolTypeLabel(card);
    final statusLabel = resolveAgentToolStatusLabel(card);

    return SizedBox(
      height: _kToolActivityRowHeight,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.fromLTRB(10 + leadingInset, 0, 8, 0),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final showTypeLabel =
                constraints.maxWidth >=
                _kToolActivityTypeSlotWidth +
                    _kToolActivityStatusSlotWidth +
                    _kToolActivityTrailingSlotWidth +
                    28;
            return Row(
              children: [
                Expanded(
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: onTap,
                    child: Row(
                      children: [
                        _StatusDot(status: status),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            resolveAgentToolTitle(card),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: primaryTextColor,
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                              letterSpacing: -0.2,
                              height: 1.05,
                            ),
                          ),
                        ),
                        SizedBox(width: showTypeLabel ? 6 : 0),
                        SizedBox(
                          width: showTypeLabel
                              ? _kToolActivityTypeSlotWidth
                              : 0,
                          child: showTypeLabel
                              ? Align(
                                  alignment: Alignment.centerRight,
                                  child: Text(
                                    toolTypeLabel,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    textAlign: TextAlign.right,
                                    style: TextStyle(
                                      color: secondaryTextColor,
                                      fontSize: 9,
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: -0.1,
                                      height: 1.05,
                                    ),
                                  ),
                                )
                              : null,
                        ),
                        const SizedBox(width: 4),
                        SizedBox(
                          width: _kToolActivityStatusSlotWidth,
                          child: Align(
                            alignment: Alignment.centerRight,
                            child: _StatusTag(
                              status: status,
                              label: statusLabel,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                SizedBox(
                  width: _kToolActivityTrailingSlotWidth,
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: trailing,
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    final palette = context.omniPalette;
    final outerColor = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.14),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.16);
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: outerColor, shape: BoxShape.circle),
      alignment: Alignment.center,
      child: Container(
        width: 3,
        height: 3,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
    );
  }
}

class _StatusTag extends StatelessWidget {
  const _StatusTag({required this.status, required this.label});

  final String status;
  final String label;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    final palette = context.omniPalette;
    final backgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.14),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.11);
    final textColor = context.isDarkTheme
        ? Color.lerp(palette.textSecondary, color, 0.38)!
        : color.withValues(alpha: 0.9);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: textColor,
          fontSize: 8.4,
          fontWeight: FontWeight.w700,
          height: 1,
        ),
      ),
    );
  }
}

class _TerminalThumbnail extends StatelessWidget {
  const _TerminalThumbnail({
    super.key,
    required this.transcript,
    required this.onTap,
  });

  final AgentToolTranscript transcript;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return PhysicalModel(
      color: kTerminalSurfaceBlack,
      borderRadius: _kToolActivityPreviewBorderRadius,
      clipBehavior: Clip.antiAlias,
      elevation: 6,
      shadowColor: kTerminalSurfaceShadow,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Ink(
            width: _kToolActivityPreviewWidth,
            height: _kToolActivityPreviewHeight,
            padding: const EdgeInsets.fromLTRB(8, 7, 8, 7),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [kTerminalSurfaceBlackElevated, kTerminalSurfaceBlack],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: _kToolActivityPreviewBorderRadius,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  transcript.promptLine,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFFF4F7FB),
                    fontSize: 6.9,
                    height: 1.05,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'monospace',
                  ),
                ),
                if (transcript.previewText.trim().isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Expanded(
                    child: Text.rich(
                      AnsiTextSpanBuilder.build(
                        transcript.previewText,
                        const TextStyle(
                          color: Color(0xFF88EEA6),
                          fontSize: 5.7,
                          height: 1.08,
                          fontFamily: 'monospace',
                        ),
                      ),
                      maxLines: 3,
                      overflow: TextOverflow.clip,
                    ),
                  ),
                ] else
                  const Spacer(),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ActivityDrawerClipper extends CustomClipper<Path> {
  const _ActivityDrawerClipper({
    required this.showPreviewCutout,
    this.bottomReveal = 0,
  });

  final bool showPreviewCutout;
  final double bottomReveal;

  @override
  Path getClip(Size size) {
    final resolvedBottomReveal = bottomReveal.clamp(0.0, size.height);
    final surfaceHeight = math.max(0.0, size.height - resolvedBottomReveal);
    final surfacePath = Path()
      ..addRRect(
        _kToolActivitySurfaceBorderRadius.toRRect(
          Rect.fromLTWH(0, 0, size.width, surfaceHeight),
        ),
      );
    if (!showPreviewCutout) {
      return surfacePath;
    }
    final previewTop =
        -(_kToolActivityPreviewHeight - _kToolActivityPreviewOverlap);
    final previewRect = Rect.fromLTWH(
      -_kToolActivitySurfaceHorizontalInset,
      previewTop,
      _kToolActivityPreviewWidth,
      _kToolActivityPreviewHeight,
    );
    final previewPath = Path()
      ..addRRect(_kToolActivityPreviewBorderRadius.toRRect(previewRect));
    return Path.combine(PathOperation.difference, surfacePath, previewPath);
  }

  @override
  bool shouldReclip(covariant _ActivityDrawerClipper oldClipper) {
    return oldClipper.showPreviewCutout != showPreviewCutout ||
        oldClipper.bottomReveal != bottomReveal;
  }
}
