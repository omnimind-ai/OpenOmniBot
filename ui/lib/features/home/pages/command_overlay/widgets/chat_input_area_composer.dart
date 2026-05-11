part of 'chat_input_area.dart';

const List<Color> _kLightComposerFlowGradientColors = <Color>[
  Color(0xFFFF6A01),
  Color(0xFFF8C91C),
  Color(0xFF8A2BE2),
  Color(0xFF00BFFF),
  Color(0xFFFF0055),
  Color(0xFFFF6A01),
];

const List<Color> _kDarkComposerFlowGradientColors = <Color>[
  Color(0xFF8C775D),
  Color(0xFFB5A27D),
  Color(0xFF99AD91),
  Color(0xFFD5C6AB),
  Color(0xFF889B80),
  Color(0xFF8C775D),
];

mixin _ChatInputAreaComposerMixin on _ChatInputAreaStateBase {
  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final composer = switch ((
      widget.useLargeComposerStyle,
      widget.useFrostedGlass,
    )) {
      (true, _) => SafeArea(child: _buildLargeComposerShell()),
      (false, true) => SafeArea(
        child: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 2, sigmaY: 2),
            child: Container(
              height: 44,
              padding: const EdgeInsets.fromLTRB(16, 0, 12, 0),
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfacePrimary.withValues(alpha: 0.86)
                    : const Color(0xE6F1F8FF),
                borderRadius: BorderRadius.circular(8),
                border: context.isDarkTheme
                    ? Border.all(
                        color: palette.borderSubtle.withValues(alpha: 0.72),
                      )
                    : null,
              ),
              child: _buildInputContent(),
            ),
          ),
        ),
      ),
      (false, false) => SafeArea(
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(8),
            boxShadow: context.isDarkTheme
                ? [
                    BoxShadow(
                      color: palette.shadowColor.withValues(alpha: 0.22),
                      blurRadius: 16,
                      offset: const Offset(0, 6),
                    ),
                  ]
                : [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.1),
                      blurRadius: 8,
                      offset: const Offset(0, 2),
                    ),
                  ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Container(
              height: 44,
              padding: const EdgeInsets.fromLTRB(16, 0, 12, 0),
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfacePrimary
                    : Colors.white,
                borderRadius: BorderRadius.circular(8),
                border: context.isDarkTheme
                    ? Border.all(color: palette.borderSubtle)
                    : null,
              ),
              child: _buildInputContent(),
            ),
          ),
        ),
      ),
    };
    return NotificationListener<SizeChangedLayoutNotification>(
      onNotification: (_) {
        _reportInputHeightAfterBuild();
        return false;
      },
      child: SizeChangedLayoutNotifier(child: composer),
    );
  }

  /// 构建输入框内容区域（按钮、文本框等）
  Widget _buildInputContent() {
    return ValueListenableBuilder<bool>(
      valueListenable: _hasTextNotifier,
      builder: (context, hasText, _) {
        return ValueListenableBuilder<bool>(
          valueListenable: _isFocusedNotifier,
          builder: (context, _, _) {
            final openClawButton = _buildOpenClawButton();
            final hasPayload = hasText || widget.attachments.isNotEmpty;
            return Row(
              children: [
                Expanded(child: _buildTextField()),
                const SizedBox(width: 9),
                _buildAnimatedButtonRow(
                  hasText: hasPayload,
                  openClawButton: openClawButton,
                ),
              ],
            );
          },
        );
      },
    );
  }

  Widget _buildLargeComposer() {
    return ValueListenableBuilder<bool>(
      valueListenable: _hasTextNotifier,
      builder: (context, hasText, _) {
        return ValueListenableBuilder<bool>(
          valueListenable: _isFocusedNotifier,
          builder: (context, _, _) {
            final hasPayload = hasText || widget.attachments.isNotEmpty;

            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (widget.attachments.isNotEmpty) ...[
                  _buildAttachmentPreview(),
                  const SizedBox(height: 8),
                ],
                if ((widget.selectedModelOverrideId ?? '')
                    .trim()
                    .isNotEmpty) ...[
                  _buildSelectedModelOverrideChip(),
                  const SizedBox(height: 8),
                ],
                _buildTextField(multiline: true),
                const SizedBox(height: 6),
                _buildLargeActionRow(hasPayload: hasPayload),
              ],
            );
          },
        );
      },
    );
  }

  Widget _buildLargeActionRow({required bool hasPayload}) {
    final contextUsageRatio = widget.contextUsageRatio;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        SizedBox(width: 28, height: 28, child: _buildLargeAddButton()),
        if (widget.onTriggerSlashCommand != null) ...[
          const SizedBox(width: 4),
          SizedBox(
            width: 28,
            height: 28,
            child: _buildSlashTriggerButton(iconSize: 20),
          ),
        ],
        const Spacer(),
        if (contextUsageRatio != null) ...[
          _ContextUsageRingButton(
            ratio: contextUsageRatio,
            tooltipMessage: widget.contextUsageTooltipMessage,
            onLongPress: widget.onLongPressContextUsageRing,
          ),
          const SizedBox(width: 4),
        ],
        if (_shouldShowCodexPermissionSelector) ...[
          SizedBox(
            width: 28,
            height: 28,
            child: _buildCodexPermissionButton(iconSize: 20),
          ),
          const SizedBox(width: 4),
        ],
        SizedBox(
          width: 28,
          height: 28,
          child: _buildTerminalButton(iconSize: 22),
        ),
        const SizedBox(width: 6),
        SizedBox(
          width: 28,
          height: 28,
          child: _buildLargeSendOrStopButton(hasPayload: hasPayload),
        ),
      ],
    );
  }

  Widget _buildSelectedModelOverrideChip() {
    final modelId = (widget.selectedModelOverrideId ?? '').trim();
    final palette = context.omniPalette;
    final chipColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : const Color(0xFFF4F7FD);
    final textColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF54627A);
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        constraints: const BoxConstraints(maxWidth: 230),
        padding: const EdgeInsets.fromLTRB(10, 5, 6, 5),
        decoration: BoxDecoration(
          color: chipColor,
          borderRadius: BorderRadius.circular(999),
          border: context.isDarkTheme
              ? Border.all(color: palette.borderSubtle)
              : null,
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: Text(
                '@$modelId',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  color: textColor,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            if (widget.onClearSelectedModelOverride != null) ...[
              const SizedBox(width: 4),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: widget.onClearSelectedModelOverride,
                child: Container(
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                    color: textColor.withValues(alpha: 0.16),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(Icons.close_rounded, size: 10, color: textColor),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildLargeAddButton() {
    return IconButton(
      padding: EdgeInsets.zero,
      iconSize: 20,
      icon: _addSvg,
      onPressed: () {
        if (widget.useAttachmentPickerForPlus &&
            widget.onPickAttachment != null) {
          if (_isPopupVisible) {
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
          }
          widget.onPickAttachment?.call();
          return;
        }

        setState(() {
          _isPopupVisible = false;
        });
        widget.onPopupVisibilityChanged?.call(false);
      },
    );
  }

  Widget _buildSlashTriggerButton({required double iconSize}) {
    return IconButton(
      key: const ValueKey('chat-input-trigger-slash-button'),
      padding: EdgeInsets.zero,
      iconSize: iconSize,
      icon: _commandSvg,
      tooltip: '命令',
      onPressed: widget.onTriggerSlashCommand == null
          ? null
          : () {
              if (_isPopupVisible) {
                setState(() => _isPopupVisible = false);
                widget.onPopupVisibilityChanged?.call(false);
              }
              widget.onTriggerSlashCommand?.call();
            },
    );
  }

  Widget _buildLargeSendOrStopButton({required bool hasPayload}) {
    final isProcessing = widget.isProcessing;
    final canSend = hasPayload;
    final canTap = isProcessing || canSend;
    final icon = isProcessing ? _pauseSvg : _sendSvg;

    return AnimatedOpacity(
      duration: _buttonAnimationDuration,
      curve: _buttonAnimationCurve,
      opacity: canTap ? 1 : 0.38,
      child: IconButton(
        padding: EdgeInsets.zero,
        iconSize: 20,
        icon: AnimatedSwitcher(
          duration: _buttonAnimationDuration,
          switchInCurve: _buttonAnimationCurve,
          switchOutCurve: _buttonAnimationCurve,
          transitionBuilder: (child, animation) {
            return FadeTransition(
              opacity: animation,
              child: ScaleTransition(scale: animation, child: child),
            );
          },
          child: SizedBox(key: ValueKey<bool>(isProcessing), child: icon),
        ),
        onPressed: !canTap
            ? null
            : () {
                if (isProcessing) {
                  widget.onCancelTask();
                } else {
                  widget.onSendMessage();
                }
              },
      ),
    );
  }

  Widget _buildLargeComposerShell() {
    final content = RepaintBoundary(child: _buildLargeComposer());
    final useFrostedGlass = widget.useFrostedGlass;
    final palette = context.omniPalette;
    return MouseRegion(
      onEnter: (_) {
        if (_isComposerHovered) return;
        setState(() => _isComposerHovered = true);
      },
      onExit: (_) {
        if (!_isComposerHovered) return;
        setState(() => _isComposerHovered = false);
      },
      child: ValueListenableBuilder<bool>(
        valueListenable: _isFocusedNotifier,
        child: content,
        builder: (context, focused, child) {
          final inputSurfaceColor = context.isDarkTheme
              ? palette.surfacePrimary
              : const Color(0xFFF9FCFF);
          final shellSurfaceColor = useFrostedGlass
              ? (context.isDarkTheme
                    ? palette.surfacePrimary.withValues(alpha: 0.82)
                    : Colors.white.withValues(alpha: 0.76))
              : inputSurfaceColor;
          final hovered = _isComposerHovered;
          const minShellHeight = 72.0;
          const shellRadius = 20.0;
          const borderInset = 1.5;
          final innerRadius = math.max(0.0, shellRadius - borderInset);
          const contentPadding = EdgeInsets.fromLTRB(14, 8, 12, 8);
          final shouldGlowStrong = focused || hovered;
          final innerBorderColor =
              (context.isDarkTheme ? palette.borderStrong : Colors.white)
                  .withValues(alpha: context.isDarkTheme ? 0.42 : 0.1);

          return AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            constraints: BoxConstraints(minHeight: minShellHeight),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(shellRadius),
              boxShadow: [
                BoxShadow(
                  color:
                      (context.isDarkTheme
                              ? palette.accentPrimary
                              : const Color(0xFF2F7BFF))
                          .withValues(
                            alpha: focused
                                ? (context.isDarkTheme ? 0.18 : 0.2)
                                : hovered
                                ? (context.isDarkTheme ? 0.12 : 0.15)
                                : (context.isDarkTheme ? 0.08 : 0.1),
                          ),
                  blurRadius: focused ? 18 : 12,
                  offset: const Offset(0, 6),
                ),
              ],
            ),
            child: Stack(
              children: [
                AnimatedPadding(
                  duration: const Duration(milliseconds: 240),
                  curve: Curves.easeOutCubic,
                  padding: EdgeInsets.all(borderInset),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(innerRadius),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(
                        sigmaX: useFrostedGlass ? 8 : 0,
                        sigmaY: useFrostedGlass ? 8 : 0,
                      ),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 220),
                        curve: Curves.easeOutCubic,
                        padding: contentPadding,
                        decoration: BoxDecoration(
                          color: shellSurfaceColor,
                          borderRadius: BorderRadius.circular(innerRadius),
                          border: Border.all(color: innerBorderColor, width: 1),
                        ),
                        child: AnimatedSize(
                          duration: const Duration(milliseconds: 220),
                          curve: Curves.easeOutCubic,
                          alignment: Alignment.topCenter,
                          child: child ?? const SizedBox.shrink(),
                        ),
                      ),
                    ),
                  ),
                ),
                Positioned.fill(
                  child: IgnorePointer(
                    child: CustomPaint(
                      painter: _ComposerFlowBorderPainter(
                        progress: _composerFlowController,
                        interactive: shouldGlowStrong,
                        focused: focused,
                        forceStrong: false,
                        radius: shellRadius,
                        strokeWidth: 1.5,
                        gradientColors: context.isDarkTheme
                            ? _kDarkComposerFlowGradientColors
                            : _kLightComposerFlowGradientColors,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildAttachmentPreview() {
    // Collect all image sources for multi-image preview
    final imageItems = widget.attachments.where((a) => a.isImage).toList();
    final imageSources = imageItems
        .map((a) => FileImageSource(a.path) as ImagePreviewSource)
        .toList();
    final heroTags = List.generate(
      imageItems.length,
      (i) => 'img_preview_input_${imageItems[i].id}',
    );

    return SizedBox(
      height: 72,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: widget.attachments.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final item = widget.attachments[index];
          if (item.isImage) {
            final imageIndex = imageItems.indexOf(item);
            return _buildImageAttachmentTile(
              item,
              imageSources,
              imageIndex,
              heroTags,
            );
          }
          return _buildFileAttachmentTile(item);
        },
      ),
    );
  }

  Widget _buildImageAttachmentTile(
    ChatInputAttachment item,
    List<ImagePreviewSource> allSources,
    int tappedIndex,
    List<String> heroTags,
  ) {
    final heroTag = heroTags[tappedIndex];
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: () => ImagePreviewOverlay.showAll(
        context,
        sources: allSources,
        initialIndex: tappedIndex.clamp(0, allSources.length - 1),
        heroTags: heroTags,
      ),
      child: Stack(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: context.isDarkTheme
                    ? palette.borderSubtle
                    : const Color(0xFFD3E3FB),
                width: 1,
              ),
              color: context.isDarkTheme
                  ? palette.surfaceSecondary
                  : const Color(0xFFF1F6FF),
            ),
            clipBehavior: Clip.antiAlias,
            child: Hero(
              tag: heroTag,
              child: Image.file(
                File(item.path),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const Center(
                  child: Icon(
                    Icons.image_not_supported_outlined,
                    size: 20,
                    color: Color(0xFF6A83AA),
                  ),
                ),
              ),
            ),
          ),
          _buildAttachmentRemoveButton(item.id),
        ],
      ),
    );
  }

  Widget _buildFileAttachmentTile(ChatInputAttachment item) {
    final sizeText = _formatAttachmentSize(item.size);
    final palette = context.omniPalette;
    final tileColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : const Color(0xFFF1F6FF);
    final tileBorderColor = context.isDarkTheme
        ? palette.borderSubtle
        : const Color(0xFFD3E3FB);
    final textColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF35517A);
    final iconColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF3B6FD6);
    return Stack(
      children: [
        Container(
          width: 160,
          height: 72,
          padding: const EdgeInsets.fromLTRB(10, 8, 28, 8),
          decoration: BoxDecoration(
            color: tileColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: tileBorderColor, width: 1),
          ),
          child: Row(
            children: [
              Icon(
                Icons.insert_drive_file_outlined,
                size: 18,
                color: iconColor,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  sizeText.isEmpty ? item.name : '${item.name}\n$sizeText',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: textColor,
                    fontWeight: FontWeight.w500,
                    height: 1.3,
                  ),
                ),
              ),
            ],
          ),
        ),
        _buildAttachmentRemoveButton(item.id),
      ],
    );
  }

  Widget _buildAttachmentRemoveButton(String attachmentId) {
    if (widget.onRemoveAttachment == null) {
      return const SizedBox.shrink();
    }
    return Positioned(
      right: 4,
      top: 4,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => widget.onRemoveAttachment?.call(attachmentId),
        child: Container(
          width: 18,
          height: 18,
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.62),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.close_rounded, size: 12, color: Colors.white),
        ),
      ),
    );
  }

  String _formatAttachmentSize(int? size) {
    if (size == null || size <= 0) return '';
    if (size < 1024) return '${size}B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)}KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  /// 构建带动画的按钮行
  Widget _buildAnimatedButtonRow({
    required bool hasText,
    required Widget? openClawButton,
  }) {
    final contextUsageRatio = widget.contextUsageRatio;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // OpenClaw 按钮 - 始终显示在固定位置
        if (openClawButton != null) ...[
          openClawButton,
          const SizedBox(width: 2),
        ],
        if (widget.onTriggerSlashCommand != null) ...[
          SizedBox(
            width: 24,
            height: 24,
            child: _buildSlashTriggerButton(iconSize: 18),
          ),
          const SizedBox(width: 2),
        ],
        if (contextUsageRatio != null) ...[
          _ContextUsageRingButton(
            ratio: contextUsageRatio,
            tooltipMessage: widget.contextUsageTooltipMessage,
            onLongPress: widget.onLongPressContextUsageRing,
          ),
          const SizedBox(width: 4),
        ],
        if (_shouldShowCodexPermissionSelector) ...[
          SizedBox(
            width: 24,
            height: 24,
            child: _buildCodexPermissionButton(iconSize: 18),
          ),
          const SizedBox(width: 2),
        ],
        SizedBox(
          width: 24,
          height: 24,
          child: _buildTerminalButton(iconSize: 20),
        ),
        const SizedBox(width: 2),
        // 发送/添加按钮
        _buildSendButton(hasText: hasText),
      ],
    );
  }

  bool get _shouldShowCodexPermissionSelector =>
      widget.codexPermissionMode != null &&
      widget.onCodexPermissionModeChanged != null;

  Widget _buildCodexPermissionButton({required double iconSize}) {
    final selected =
        widget.codexPermissionMode ?? CodexPermissionMode.fullAccess;
    final palette = context.omniPalette;
    final selectedColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF2F65D9);
    final inactiveColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF5E6C84);

    return PopupMenuButton<CodexPermissionMode>(
      key: const ValueKey('chat-input-codex-permission-button'),
      padding: EdgeInsets.zero,
      tooltip: _codexPermissionTooltip(),
      position: PopupMenuPosition.over,
      offset: const Offset(0, -8),
      color: context.isDarkTheme ? palette.surfaceElevated : Colors.white,
      elevation: 10,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      constraints: const BoxConstraints(minWidth: 184),
      onSelected: widget.onCodexPermissionModeChanged,
      itemBuilder: (context) {
        return CodexPermissionMode.values
            .map((mode) {
              final isSelected = mode == selected;
              return PopupMenuItem<CodexPermissionMode>(
                key: ValueKey(
                  'chat-input-codex-permission-option-${mode.name}',
                ),
                value: mode,
                height: 42,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _buildCodexPermissionIcon(
                      mode,
                      size: 18,
                      color: isSelected ? selectedColor : inactiveColor,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        _codexPermissionLabel(mode),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 14,
                          height: 1.2,
                          color: context.isDarkTheme
                              ? palette.textPrimary
                              : const Color(0xFF232D3D),
                          fontWeight: isSelected
                              ? FontWeight.w600
                              : FontWeight.w500,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    AnimatedOpacity(
                      duration: _buttonAnimationDuration,
                      opacity: isSelected ? 1 : 0,
                      child: Icon(
                        Icons.check_rounded,
                        size: 18,
                        color: selectedColor,
                      ),
                    ),
                  ],
                ),
              );
            })
            .toList(growable: false);
      },
      child: AnimatedContainer(
        duration: _buttonAnimationDuration,
        curve: _buttonAnimationCurve,
        width: 24,
        height: 24,
        decoration: BoxDecoration(
          color: context.isDarkTheme
              ? palette.surfaceSecondary.withValues(alpha: 0.72)
              : const Color(0xFFEAF1FF),
          shape: BoxShape.circle,
        ),
        child: Center(
          child: _buildCodexPermissionIcon(
            selected,
            size: iconSize,
            color: selectedColor,
          ),
        ),
      ),
    );
  }

  String _codexPermissionTooltip() {
    return Localizations.localeOf(context).languageCode == 'en'
        ? 'Codex permissions'
        : 'Codex 权限';
  }

  String _codexPermissionLabel(CodexPermissionMode mode) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return switch (mode) {
      CodexPermissionMode.defaultMode =>
        english ? 'Default permissions' : '默认权限',
      CodexPermissionMode.autoReview => english ? 'Auto review' : '自动审查',
      CodexPermissionMode.fullAccess => english ? 'Full access' : '完全访问权限',
    };
  }

  String _codexPermissionIconAsset(CodexPermissionMode mode) {
    return switch (mode) {
      CodexPermissionMode.defaultMode => _kCodexPermissionDefaultIconAsset,
      CodexPermissionMode.autoReview => _kCodexPermissionAutoReviewIconAsset,
      CodexPermissionMode.fullAccess => _kCodexPermissionFullAccessIconAsset,
    };
  }

  Widget _buildCodexPermissionIcon(
    CodexPermissionMode mode, {
    required double size,
    required Color color,
  }) {
    return SvgPicture.asset(
      _codexPermissionIconAsset(mode),
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }

  Widget _buildTerminalButton({required double iconSize}) {
    return IconButton(
      padding: EdgeInsets.zero,
      tooltip: Localizations.localeOf(context).languageCode == 'en'
          ? 'Open terminal'
          : '打开终端',
      iconSize: iconSize,
      icon: SizedBox(
        width: 24,
        height: 24,
        child: Center(
          child: SizedBox(
            width: iconSize,
            height: iconSize,
            child: _terminalSvg,
          ),
        ),
      ),
      onPressed: () {
        unawaited(openTerminalFromInput());
      },
    );
  }

  /// 统一的输入框组件
  Widget _buildTextField({bool multiline = false}) {
    final palette = context.omniPalette;
    final keyboardType = multiline
        ? TextInputType.multiline
        : TextInputType.text;
    final textInputAction = multiline
        ? TextInputAction.newline
        : TextInputAction.send;
    final textColor = context.isDarkTheme
        ? palette.textPrimary
        : const Color(0xFF353E53);
    final hintColor = context.isDarkTheme
        ? palette.textTertiary
        : const Color(0x80353E53);
    final textStyle = TextStyle(
      fontSize: multiline ? 15.0 : 14.0,
      height: multiline ? 1.45 : 1.43,
      color: textColor,
      letterSpacing: 0.333,
    );
    return GestureDetector(
      onTap: () {
        widget.focusNode.requestFocus();
      },
      child: AbsorbPointer(
        absorbing: !widget.focusNode.hasFocus,
        child: TextField(
          controller: widget.controller,
          focusNode: widget.focusNode,
          scrollController: _textFieldScrollController,
          keyboardType: keyboardType,
          textInputAction: textInputAction,
          minLines: 1,
          maxLines: multiline ? 2 : 1,
          scrollPhysics: const ClampingScrollPhysics(),
          onSubmitted: multiline
              ? null
              : (_) {
                  if (widget.controller.text.trim().isNotEmpty) {
                    widget.onSendMessage();
                  } else {
                    widget.focusNode.requestFocus();
                  }
                },
          textAlignVertical: multiline
              ? TextAlignVertical.top
              : TextAlignVertical.center,
          textCapitalization: TextCapitalization.sentences,
          style: textStyle,
          contextMenuBuilder: (context, editableTextState) =>
              TextInputContextMenu(editableTextState: editableTextState),
          decoration: InputDecoration(
            hintText: Localizations.localeOf(context).languageCode == 'en'
                ? 'Type your message'
                : '请输入内容',
            hintStyle: TextStyle(
              fontSize: multiline ? 15.0 : 14.0,
              color: hintColor,
              height: multiline ? 1.45 : 1.43,
              letterSpacing: 0.333,
            ),
            filled: false,
            fillColor: Colors.transparent,
            border: InputBorder.none,
            enabledBorder: InputBorder.none,
            focusedBorder: InputBorder.none,
            disabledBorder: InputBorder.none,
            errorBorder: InputBorder.none,
            focusedErrorBorder: InputBorder.none,
            contentPadding: EdgeInsets.symmetric(vertical: multiline ? 2 : 12),
            isDense: true,
          ),
        ),
      ),
    );
  }

  /// OpenClaw 开关按钮（位于语音按钮左侧）
  /// 点击切换开关，长按唤出配置面板
  Widget? _buildOpenClawButton() {
    if (widget.openClawEnabled == null || widget.onToggleOpenClaw == null) {
      return null;
    }

    final isEnabled = widget.openClawEnabled == true;

    return GestureDetector(
      onLongPress: widget.onLongPressOpenClaw,
      child: SizedBox(
        width: 24,
        height: 24,
        child: IconButton(
          padding: EdgeInsets.zero,
          iconSize: 20,
          icon: AnimatedSwitcher(
            duration: _buttonAnimationDuration,
            transitionBuilder: (child, animation) {
              return FadeTransition(
                opacity: animation,
                child: ScaleTransition(scale: animation, child: child),
              );
            },
            child: SvgPicture.asset(
              isEnabled
                  ? 'assets/home/openclaw.svg'
                  : 'assets/home/openclaw_gray.svg',
              key: ValueKey<bool>(isEnabled),
              width: 20,
              height: 20,
            ),
          ),
          onPressed: () => widget.onToggleOpenClaw?.call(!isEnabled),
        ),
      ),
    );
  }

  /// 右侧发送/添加按钮
  Widget _buildSendButton({required bool hasText}) {
    Widget icon;
    VoidCallback? onPressed;
    String iconKey;

    if (widget.isProcessing) {
      icon = _pauseSvg;
      iconKey = 'pause';
      onPressed = () {
        widget.onCancelTask();
      };
    } else if (hasText) {
      icon = _sendSvg;
      iconKey = 'send';
      onPressed = () {
        widget.onSendMessage();
      };
    } else {
      icon = _addSvg;
      iconKey = 'add';
      if (widget.useAttachmentPickerForPlus &&
          widget.onPickAttachment != null) {
        onPressed = () {
          if (_isPopupVisible) {
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
          }
          widget.onPickAttachment?.call();
        };
      } else {
        if (_isPopupVisible) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
          });
        }
        onPressed = null;
      }
    }

    return SizedBox(
      width: 24,
      height: 24,
      child: IconButton(
        padding: EdgeInsets.zero,
        iconSize: 20,
        icon: AnimatedSwitcher(
          duration: _buttonAnimationDuration,
          switchInCurve: _buttonAnimationCurve,
          switchOutCurve: _buttonAnimationCurve,
          transitionBuilder: (child, animation) {
            return FadeTransition(
              opacity: animation,
              child: ScaleTransition(scale: animation, child: child),
            );
          },
          child: SizedBox(key: ValueKey<String>(iconKey), child: icon),
        ),
        onPressed: onPressed,
      ),
    );
  }
}

class _ComposerFlowBorderPainter extends CustomPainter {
  final Animation<double> progress;
  final bool interactive;
  final bool focused;
  final bool forceStrong;
  final double radius;
  final double strokeWidth;
  final List<Color> gradientColors;

  _ComposerFlowBorderPainter({
    required this.progress,
    required this.interactive,
    required this.focused,
    required this.forceStrong,
    required this.radius,
    required this.strokeWidth,
    required this.gradientColors,
  }) : super(repaint: progress);

  @override
  void paint(Canvas canvas, Size size) {
    final flow = progress.value;
    final breath = (math.sin(flow * 2 * math.pi) + 1) / 2;
    final speed = focused ? 1.6 : 1.0;
    final shift = ((flow * speed) % 1.0) * 2 - 1;
    final rawOpacity = forceStrong
        ? 0.9
        : (interactive ? (focused ? 1.0 : 0.82) : (0.3 + breath * 0.4));
    final clampedOpacity = rawOpacity.clamp(0.0, 1.0);
    if (clampedOpacity <= 0 || size.isEmpty) return;

    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(
      rect.deflate(strokeWidth / 2),
      Radius.circular(radius - strokeWidth / 2),
    );
    final gradient = LinearGradient(
      begin: Alignment(-1 + shift, 0),
      end: Alignment(1 + shift, 0),
      colors: gradientColors
          .map((color) => color.withValues(alpha: clampedOpacity))
          .toList(growable: false),
      stops: const [0.0, 0.2, 0.4, 0.62, 0.82, 1.0],
    );

    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..isAntiAlias = true
      ..shader = gradient.createShader(rect);

    canvas.drawRRect(rrect, paint);
  }

  @override
  bool shouldRepaint(covariant _ComposerFlowBorderPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.interactive != interactive ||
        oldDelegate.focused != focused ||
        oldDelegate.forceStrong != forceStrong ||
        oldDelegate.radius != radius ||
        oldDelegate.strokeWidth != strokeWidth ||
        oldDelegate.gradientColors != gradientColors;
  }
}
