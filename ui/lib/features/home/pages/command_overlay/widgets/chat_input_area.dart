// lib/widgets/chat_input_area.dart
import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter/material.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/text_input_context_menu.dart';

part 'chat_input_area_composer.dart';
part 'chat_input_area_popup.dart';

const String _kInputTerminalIconAsset = 'assets/home/input_terminal_icon.svg';

const String _kLucideCommandSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-command-icon lucide-command">'
    '<path d="M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/>'
    '</svg>';

const String _kCodexPermissionDefaultIconAsset =
    'assets/home/chat/permission_hand.svg';
const String _kCodexPermissionAutoReviewIconAsset =
    'assets/home/chat/codex.svg';
const String _kCodexPermissionFullAccessIconAsset =
    'assets/home/chat/permission_shield_alert.svg';

enum CodexPermissionMode { defaultMode, autoReview, fullAccess }

class ChatInputAttachment {
  final String id;
  final String name;
  final String path;
  final int? size;
  final String? mimeType;
  final bool isImage;
  final String? promptPath;
  final bool sendToModel;

  const ChatInputAttachment({
    required this.id,
    required this.name,
    required this.path,
    this.size,
    this.mimeType,
    this.isImage = false,
    this.promptPath,
    this.sendToModel = true,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'path': path,
      if (size != null) 'size': size,
      if (mimeType != null) 'mimeType': mimeType,
      'isImage': isImage,
      if ((promptPath ?? '').trim().isNotEmpty)
        'promptPath': promptPath!.trim(),
      if (!sendToModel) 'sendToModel': false,
    };
  }
}

class ChatInputArea extends StatefulWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool isProcessing;
  final VoidCallback onSendMessage;
  final VoidCallback onCancelTask;
  final ValueChanged<bool>? onPopupVisibilityChanged;
  final ValueChanged<double>? onInputHeightChanged;
  final bool? openClawEnabled;
  final ValueChanged<bool>? onToggleOpenClaw;
  final VoidCallback? onLongPressOpenClaw;
  final FutureOr<void> Function()? onTerminalTap;

  /// 是否使用毛玻璃效果（command_overlay 使用毛玻璃，chatbotsheet 使用白色+阴影）
  final bool useFrostedGlass;
  final bool useLargeComposerStyle;
  final bool useAttachmentPickerForPlus;
  final Future<void> Function()? onPickAttachment;
  final List<ChatInputAttachment> attachments;
  final ValueChanged<String>? onRemoveAttachment;
  final VoidCallback? onTriggerSlashCommand;
  final String? selectedModelOverrideId;
  final VoidCallback? onClearSelectedModelOverride;
  final double? contextUsageRatio;
  final String? contextUsageTooltipMessage;
  final VoidCallback? onLongPressContextUsageRing;
  final CodexPermissionMode? codexPermissionMode;
  final ValueChanged<CodexPermissionMode>? onCodexPermissionModeChanged;

  const ChatInputArea({
    super.key,
    required this.controller,
    required this.focusNode,
    required this.isProcessing,
    required this.onSendMessage,
    required this.onCancelTask,
    this.onPopupVisibilityChanged,
    this.onInputHeightChanged,
    this.openClawEnabled,
    this.onToggleOpenClaw,
    this.onLongPressOpenClaw,
    this.onTerminalTap,
    this.useFrostedGlass = false,
    this.useLargeComposerStyle = false,
    this.useAttachmentPickerForPlus = false,
    this.onPickAttachment,
    this.attachments = const [],
    this.onRemoveAttachment,
    this.onTriggerSlashCommand,
    this.selectedModelOverrideId,
    this.onClearSelectedModelOverride,
    this.contextUsageRatio,
    this.contextUsageTooltipMessage,
    this.onLongPressContextUsageRing,
    this.codexPermissionMode,
    this.onCodexPermissionModeChanged,
  });

  @override
  State<ChatInputArea> createState() => ChatInputAreaState();
}

class _ContextUsageRing extends StatelessWidget {
  const _ContextUsageRing({required this.ratio});

  final double ratio;

  @override
  Widget build(BuildContext context) {
    final normalized = ratio.isFinite ? ratio : 0.0;
    final progress = normalized.clamp(0.0, 1.0).toDouble();
    final palette = context.omniPalette;
    final color = context.isDarkTheme
        ? normalized >= 1.0
              ? const Color(0xFFB97862)
              : normalized >= 0.85
              ? const Color(0xFFB39B6B)
              : palette.accentPrimary
        : normalized >= 1.0
        ? const Color(0xFFD65A3A)
        : normalized >= 0.85
        ? const Color(0xFFC69234)
        : const Color(0xFF5A8DDE);
    final trackColor = context.isDarkTheme
        ? Color.lerp(
            palette.surfaceElevated,
            palette.borderStrong,
            0.62,
          )!.withValues(alpha: 0.92)
        : const Color(0x18000000);

    return SizedBox(
      width: 18,
      height: 18,
      child: TweenAnimationBuilder<double>(
        tween: Tween<double>(begin: 0, end: progress),
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        builder: (context, value, _) {
          return CustomPaint(
            painter: _ContextUsageRingPainter(
              progress: value,
              color: color,
              trackColor: trackColor,
            ),
          );
        },
      ),
    );
  }
}

class _ContextUsageRingButton extends StatelessWidget {
  const _ContextUsageRingButton({
    required this.ratio,
    this.tooltipMessage,
    this.onLongPress,
  });

  final double ratio;
  final String? tooltipMessage;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final child = SizedBox(
      width: 22,
      height: 22,
      child: Center(child: _ContextUsageRing(ratio: ratio)),
    );
    final interactiveChild = onLongPress == null
        ? child
        : GestureDetector(
            behavior: HitTestBehavior.opaque,
            onLongPress: onLongPress,
            child: child,
          );
    final tooltip = tooltipMessage?.trim() ?? '';
    if (tooltip.isEmpty) {
      return interactiveChild;
    }
    return Tooltip(
      message: tooltip,
      triggerMode: TooltipTriggerMode.tap,
      waitDuration: Duration.zero,
      showDuration: const Duration(seconds: 3),
      preferBelow: false,
      verticalOffset: 12,
      decoration: BoxDecoration(
        color: const Color(0xFF172033),
        borderRadius: BorderRadius.circular(14),
        boxShadow: const [
          BoxShadow(
            color: Color(0x24172033),
            blurRadius: 24,
            offset: Offset(0, 12),
          ),
        ],
      ),
      textStyle: const TextStyle(
        color: Colors.white,
        fontSize: 12,
        height: 1.45,
        fontWeight: FontWeight.w500,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: interactiveChild,
    );
  }
}

class _ContextUsageRingPainter extends CustomPainter {
  const _ContextUsageRingPainter({
    required this.progress,
    required this.color,
    required this.trackColor,
  });

  final double progress;
  final Color color;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final strokeWidth = 1.8;
    final radius = (math.min(size.width, size.height) - strokeWidth) / 2;
    final center = Offset(size.width / 2, size.height / 2);
    final rect = Rect.fromCircle(center: center, radius: radius);

    final trackPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round
      ..color = trackColor;
    final progressPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round
      ..color = color;

    canvas.drawArc(rect, 0, math.pi * 2, false, trackPaint);
    if (progress <= 0) return;
    canvas.drawArc(
      rect,
      -math.pi / 2,
      math.pi * 2 * progress.clamp(0.0, 1.0),
      false,
      progressPaint,
    );
  }

  @override
  bool shouldRepaint(covariant _ContextUsageRingPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.color != color ||
        oldDelegate.trackColor != trackColor;
  }
}

class ChatInputAreaState extends _ChatInputAreaStateBase
    with _ChatInputAreaComposerMixin, _ChatInputAreaPopupMixin {}

abstract class _ChatInputAreaStateBase extends State<ChatInputArea>
    with TickerProviderStateMixin {
  late ValueNotifier<bool> _hasTextNotifier;
  late ValueNotifier<bool> _isFocusedNotifier;
  bool _isPopupVisible = false;

  final ScrollController _textFieldScrollController = ScrollController();

  bool get isPopupVisible => _isPopupVisible;
  double _lastReportedInputHeight = 44;
  bool _inputHeightReportScheduled = false;
  bool _isComposerHovered = false;
  late AnimationController _composerFlowController;

  late Widget _terminalSvg;
  late Widget _sendSvg;
  late Widget _pauseSvg;
  late Widget _addSvg;
  late Widget _commandSvg;

  // 按钮动画相关
  final Duration _buttonAnimationDuration = const Duration(milliseconds: 200);
  final Curve _buttonAnimationCurve = Curves.easeInOut;

  @override
  void initState() {
    super.initState();
    _hasTextNotifier = ValueNotifier<bool>(false);
    _isFocusedNotifier = ValueNotifier<bool>(false);
    widget.controller.addListener(_onTextChanged);
    widget.focusNode.addListener(_onFocusChanged);

    _terminalSvg = const SizedBox.shrink();
    _sendSvg = const SizedBox.shrink();
    _pauseSvg = const SizedBox.shrink();
    _addSvg = const SizedBox.shrink();
    _commandSvg = const SizedBox.shrink();
    _composerFlowController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 8000),
    )..repeat();
    _reportInputHeightAfterBuild();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final palette = context.omniPalette;
    _terminalSvg = SvgPicture.asset(
      _kInputTerminalIconAsset,
      colorFilter: ColorFilter.mode(palette.accentPrimary, BlendMode.srcIn),
    );
    _sendSvg = context.isDarkTheme
        ? _buildDarkActionButtonIcon(
            size: 24,
            backgroundColor: Color.lerp(
              palette.surfaceElevated,
              palette.accentPrimary,
              0.34,
            )!,
            foreground: Icon(
              Icons.arrow_upward_rounded,
              size: 15,
              color: palette.pageBackground,
            ),
          )
        : _buildComposerIconAsset(
            'assets/home/send_icon.svg',
            width: 24,
            height: 24,
          );
    _pauseSvg = context.isDarkTheme
        ? _buildDarkActionButtonIcon(
            size: 20,
            backgroundColor: Color.lerp(
              palette.surfaceElevated,
              palette.accentPrimary,
              0.34,
            )!,
            foreground: Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                color: palette.pageBackground,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          )
        : _buildComposerIconAsset(
            'assets/home/input_pause_icon.svg',
            width: 20,
            height: 20,
          );
    _addSvg = context.isDarkTheme
        ? _buildDarkActionButtonIcon(
            size: 20,
            backgroundColor: palette.surfaceSecondary,
            borderColor: palette.borderSubtle,
            foreground: Icon(
              Icons.add_rounded,
              size: 14,
              color: palette.textPrimary,
            ),
          )
        : _buildComposerIconAsset(
            'assets/home/input_add_icon.svg',
            width: 20,
            height: 20,
          );
    _commandSvg = context.isDarkTheme
        ? _buildDarkActionButtonIcon(
            size: 20,
            backgroundColor: palette.surfaceSecondary,
            borderColor: palette.borderSubtle,
            foreground: SvgPicture.string(
              _kLucideCommandSvg,
              width: 12,
              height: 12,
              colorFilter: ColorFilter.mode(
                palette.textPrimary,
                BlendMode.srcIn,
              ),
            ),
          )
        : SvgPicture.string(
            _kLucideCommandSvg,
            width: 20,
            height: 20,
            colorFilter: const ColorFilter.mode(
              Color(0xFF54627A),
              BlendMode.srcIn,
            ),
          );
  }

  Widget _buildComposerIconAsset(
    String assetPath, {
    required double width,
    required double height,
  }) {
    return SvgPicture.asset(assetPath, width: width, height: height);
  }

  Widget _buildDarkActionButtonIcon({
    required double size,
    required Widget foreground,
    required Color backgroundColor,
    Color? borderColor,
  }) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: backgroundColor,
        shape: BoxShape.circle,
        border: borderColor == null ? null : Border.all(color: borderColor),
      ),
      alignment: Alignment.center,
      child: foreground,
    );
  }

  Future<void> openTerminalFromInput() async {
    try {
      final handler = widget.onTerminalTap;
      if (handler != null) {
        await handler();
      } else {
        await openNativeTerminal();
      }
    } catch (error) {
      if (!mounted) {
        return;
      }
      final messenger = ScaffoldMessenger.maybeOf(context);
      messenger?.showSnackBar(SnackBar(content: Text('打开终端失败: $error')));
    }
  }

  void _onTextChanged() {
    _hasTextNotifier.value = widget.controller.text.trim().isNotEmpty;
    _reportInputHeightAfterBuild();
  }

  void _onFocusChanged() {
    _isFocusedNotifier.value = widget.focusNode.hasFocus;
    _reportInputHeightAfterBuild();
  }

  @override
  void didUpdateWidget(covariant ChatInputArea oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.attachments != widget.attachments ||
        oldWidget.useLargeComposerStyle != widget.useLargeComposerStyle ||
        oldWidget.useFrostedGlass != widget.useFrostedGlass ||
        oldWidget.selectedModelOverrideId != widget.selectedModelOverrideId) {
      _reportInputHeightAfterBuild();
    }
  }

  @override
  void dispose() {
    _textFieldScrollController.dispose();
    _hasTextNotifier.dispose();
    _isFocusedNotifier.dispose();
    _composerFlowController.dispose();
    widget.controller.removeListener(_onTextChanged);
    widget.focusNode.removeListener(_onFocusChanged);
    super.dispose();
  }

  void _reportInputHeightAfterBuild() {
    if (_inputHeightReportScheduled) return;
    _inputHeightReportScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _inputHeightReportScheduled = false;
      if (!mounted) return;
      final renderBox = context.findRenderObject() as RenderBox?;
      if (renderBox == null || !renderBox.hasSize) return;
      final height = renderBox.size.height;
      if ((height - _lastReportedInputHeight).abs() < 0.5) return;
      _lastReportedInputHeight = height;
      widget.onInputHeightChanged?.call(height);
    });
  }
}
