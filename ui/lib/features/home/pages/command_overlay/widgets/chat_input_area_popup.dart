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
          requiresManualRecordingPermissions: true,
        ),
    ];
    if (actions.isEmpty) return const SizedBox.shrink();
    final palette = context.omniPalette;
    final showPermissionNotice =
        _hasManualRecordingAction &&
        (_isCheckingManualRecordingPermissions ||
            _isManualRecordingPermissionBlocked);
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
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (showPermissionNotice) ...[
              _buildManualRecordingPermissionNotice(),
              const SizedBox(height: 8),
            ],
            Row(
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
          ],
        ),
      ),
    );
  }

  Widget _buildTrajectoryPopupItem(_TrajectoryPopupAction action) {
    final palette = context.omniPalette;
    final blocked =
        action.requiresManualRecordingPermissions &&
        _isManualRecordingPermissionBlocked;
    final accentColor = blocked
        ? palette.textTertiary
        : context.isDarkTheme
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
            unawaited(_handleTrajectoryPopupActionTap(action));
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

  Widget _buildManualRecordingPermissionNotice() {
    final palette = context.omniPalette;
    final locale = Localizations.localeOf(context);
    final permissionCheck = _manualRecordingPermissionCheck;
    final missingText = permissionCheck?.missingPermissionText(locale: locale);
    final message = _isCheckingManualRecordingPermissions
        ? AppTextLocalizer.choose(
            zh: '正在检查录制权限',
            en: 'Checking recording permissions',
            locale: locale,
          )
        : AppTextLocalizer.choose(
            zh: '${missingText?.isNotEmpty == true ? missingText : '无障碍辅助权限、悬浮窗权限'}未开启，无法录制',
            en: '${missingText?.isNotEmpty == true ? missingText : 'Accessibility, Overlay permission'} required before recording',
            locale: locale,
          );
    final warningColor = context.isDarkTheme
        ? const Color(0xFFE4B06A)
        : const Color(0xFFD97706);

    return Container(
      width: 258,
      padding: const EdgeInsets.fromLTRB(10, 9, 8, 9),
      decoration: BoxDecoration(
        color: warningColor.withValues(
          alpha: context.isDarkTheme ? 0.16 : 0.10,
        ),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: warningColor.withValues(alpha: 0.28)),
      ),
      child: Row(
        children: [
          Icon(Icons.lock_outline_rounded, size: 16, color: warningColor),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: context.isDarkTheme
                    ? palette.textPrimary
                    : const Color(0xFF7C2D12),
                fontSize: 12,
                fontWeight: FontWeight.w600,
                height: 1.25,
                letterSpacing: 0,
              ),
            ),
          ),
          const SizedBox(width: 8),
          TextButton(
            onPressed: _isCheckingManualRecordingPermissions
                ? null
                : () {
                    unawaited(_openManualRecordingPermissionSheet());
                  },
            style: TextButton.styleFrom(
              minimumSize: const Size(52, 30),
              padding: const EdgeInsets.symmetric(horizontal: 8),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              foregroundColor: warningColor,
              textStyle: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0,
              ),
            ),
            child: Text(
              AppTextLocalizer.choose(zh: '去开启', en: 'Open', locale: locale),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _handleTrajectoryPopupActionTap(
    _TrajectoryPopupAction action,
  ) async {
    if (action.requiresManualRecordingPermissions) {
      await _refreshManualRecordingPermissions();
      if (!mounted) return;
      if (_isManualRecordingPermissionBlocked) {
        await _openManualRecordingPermissionSheet();
        return;
      }
    }
    if (!mounted) return;
    setState(() => _isPopupVisible = false);
    widget.onPopupVisibilityChanged?.call(false);
    unawaited(Future<void>.sync(action.onTap));
  }

  Future<void> _openManualRecordingPermissionSheet() async {
    final authorized = await ManualRecordingPermissionGuard.ensureAuthorized(
      context,
    );
    if (!mounted) return;
    await _refreshManualRecordingPermissions();
    if (!mounted || !authorized || widget.onManualRecordingTap == null) {
      return;
    }
    setState(() => _isPopupVisible = false);
    widget.onPopupVisibilityChanged?.call(false);
    unawaited(Future<void>.sync(widget.onManualRecordingTap!));
  }
}

class _TrajectoryPopupAction {
  const _TrajectoryPopupAction({
    required this.icon,
    required this.label,
    required this.tooltip,
    required this.onTap,
    this.requiresManualRecordingPermissions = false,
  });

  final IconData icon;
  final String label;
  final String tooltip;
  final FutureOr<void> Function() onTap;
  final bool requiresManualRecordingPermissions;
}
