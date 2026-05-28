part of 'chat_input_area.dart';

mixin _ChatInputAreaPopupMixin on _ChatInputAreaStateBase {
  Widget buildPopupMenu() {
    if (!_isPopupVisible) return const SizedBox.shrink();
    final actions = <_TrajectoryPopupAction>[
      if (widget.onViewTrajectoriesTap != null)
        _TrajectoryPopupAction(
          icon: Icons.history_rounded,
          label: context.l10n.chatInputViewTrajectories,
          tooltip: context.l10n.chatInputViewTrajectoriesTooltip,
          onTap: widget.onViewTrajectoriesTap!,
        ),
      if (widget.onViewCurrentTrajectoryTap != null)
        _TrajectoryPopupAction(
          icon: Icons.trip_origin_rounded,
          label: context.l10n.chatInputViewCurrentTrajectory,
          tooltip: context.l10n.chatInputViewCurrentTrajectoryTooltip,
          onTap: widget.onViewCurrentTrajectoryTap!,
        ),
      if (widget.onManualRecordingTap != null)
        _TrajectoryPopupAction(
          icon: Icons.fiber_manual_record_rounded,
          label: context.l10n.chatInputRecordTrajectory,
          tooltip: context.l10n.chatInputRecordTrajectoryTooltip,
          onTap: widget.onManualRecordingTap!,
        ),
    ];
    if (actions.isEmpty) return const SizedBox.shrink();
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: Container(
        key: const ValueKey('chat-input-trajectory-popup'),
        padding: const EdgeInsets.fromLTRB(10, 10, 10, 9),
        decoration: BoxDecoration(
          color: context.isDarkTheme ? palette.surfaceElevated : Colors.white,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: [
            BoxShadow(
              color: palette.shadowColor.withValues(alpha: 0.72),
              blurRadius: 18,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: actions
              .asMap()
              .entries
              .map(
                (entry) => Padding(
                  padding: EdgeInsets.only(left: entry.key == 0 ? 0 : 8),
                  child: _buildTrajectoryPopupItem(entry.value),
                ),
              )
              .toList(growable: false),
        ),
      ),
    );
  }

  Widget _buildTrajectoryPopupItem(_TrajectoryPopupAction action) {
    final palette = context.omniPalette;
    final accentColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF2F65D9);
    return Tooltip(
      message: action.tooltip,
      child: Semantics(
        button: true,
        label: action.tooltip,
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: () {
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
            unawaited(Future<void>.sync(action.onTap));
          },
          child: SizedBox(
            width: 78,
            height: 72,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: accentColor.withValues(alpha: 0.12),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(action.icon, size: 22, color: accentColor),
                ),
                const SizedBox(height: 6),
                Text(
                  action.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: palette.textPrimary,
                    height: 1.2,
                    letterSpacing: 0,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _TrajectoryPopupAction {
  const _TrajectoryPopupAction({
    required this.icon,
    required this.label,
    required this.tooltip,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final String tooltip;
  final FutureOr<void> Function() onTap;
}
