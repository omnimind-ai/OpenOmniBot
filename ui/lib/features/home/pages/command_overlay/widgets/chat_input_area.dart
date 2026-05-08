// lib/widgets/chat_input_area.dart
import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:permission_handler/permission_handler.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/speech_channel_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/text_input_context_menu.dart';

part 'chat_input_area_recording.dart';
part 'chat_input_area_composer.dart';
part 'chat_input_area_popup.dart';

const String _kLucideMicSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="url(#paint0_linear_mic)" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-mic-icon lucide-mic">'
    '<path d="M12 19v3"/>'
    '<path d="M19 10v2a7 7 0 0 1-14 0v-2"/>'
    '<rect x="9" y="2" width="6" height="13" rx="3"/>'
    '<defs>'
    '<linearGradient id="paint0_linear_mic" x1="3.4" y1="-1.8" x2="27.6" y2="7.9" gradientUnits="userSpaceOnUse">'
    '<stop stop-color="#1930D9"/>'
    '<stop offset="1" stop-color="#2DA5F0"/>'
    '</linearGradient>'
    '</defs>'
    '</svg>';

const String _kLucideMicSvgDark =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="url(#paint0_linear_mic_dark)" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-mic-icon lucide-mic">'
    '<path d="M12 19v3"/>'
    '<path d="M19 10v2a7 7 0 0 1-14 0v-2"/>'
    '<rect x="9" y="2" width="6" height="13" rx="3"/>'
    '<defs>'
    '<linearGradient id="paint0_linear_mic_dark" x1="3.4" y1="-1.8" x2="27.6" y2="7.9" gradientUnits="userSpaceOnUse">'
    '<stop stop-color="#8C775D"/>'
    '<stop offset="1" stop-color="#9DAE95"/>'
    '</linearGradient>'
    '</defs>'
    '</svg>';

const String _kLucideCommandSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-command-icon lucide-command">'
    '<path d="M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/>'
    '</svg>';

enum RecordingState { idle, starting, recording, stopping, waitingServerStop }

class ChatInputAttachment {
  final String id;
  final String name;
  final String path;
  final int? size;
  final String? mimeType;
  final bool isImage;

  const ChatInputAttachment({
    required this.id,
    required this.name,
    required this.path,
    this.size,
    this.mimeType,
    this.isImage = false,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'path': path,
      if (size != null) 'size': size,
      if (mimeType != null) 'mimeType': mimeType,
      'isImage': isImage,
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
  final ValueChanged<RecordingState>? onRecordingStateChanged;
  final ValueChanged<double>? onInputHeightChanged;
  final bool? openClawEnabled;
  final ValueChanged<bool>? onToggleOpenClaw;
  final VoidCallback? onLongPressOpenClaw;

  /// 是否使用毛玻璃效果（command_overlay 使用毛玻璃，chatbotsheet 使用白色+阴影）
  final bool useFrostedGlass;
  final bool useLargeComposerStyle;
  final bool useAttachmentPickerForPlus;
  final Future<void> Function()? onPickAttachment;
  final List<ChatInputAttachment> attachments;
  final ValueChanged<String>? onRemoveAttachment;
  final VoidCallback? onTriggerSlashCommand;
  final VoidCallback? onOpenWorkbenchProject;
  final String? selectedModelOverrideId;
  final VoidCallback? onClearSelectedModelOverride;
  final double? contextUsageRatio;
  final String? contextUsageTooltipMessage;
  final VoidCallback? onLongPressContextUsageRing;

  const ChatInputArea({
    super.key,
    required this.controller,
    required this.focusNode,
    required this.isProcessing,
    required this.onSendMessage,
    required this.onCancelTask,
    this.onPopupVisibilityChanged,
    this.onRecordingStateChanged,
    this.onInputHeightChanged,
    this.openClawEnabled,
    this.onToggleOpenClaw,
    this.onLongPressOpenClaw,
    this.useFrostedGlass = false,
    this.useLargeComposerStyle = false,
    this.useAttachmentPickerForPlus = false,
    this.onPickAttachment,
    this.attachments = const [],
    this.onRemoveAttachment,
    this.onTriggerSlashCommand,
    this.onOpenWorkbenchProject,
    this.selectedModelOverrideId,
    this.onClearSelectedModelOverride,
    this.contextUsageRatio,
    this.contextUsageTooltipMessage,
    this.onLongPressContextUsageRing,
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
    with
        _ChatInputAreaRecordingMixin,
        _ChatInputAreaComposerMixin,
        _ChatInputAreaPopupMixin {}

abstract class _ChatInputAreaStateBase extends State<ChatInputArea>
    with TickerProviderStateMixin {
  late ValueNotifier<bool> _hasTextNotifier;
  late ValueNotifier<bool> _isFocusedNotifier;
  bool _isPopupVisible = false;

  // 录音相关状态
  RecordingState _recordingState = RecordingState.idle;
  String _textBeforeRecording = ''; // 录音开始前的文本
  String _currentTranscript = ''; // 当前语音识别的文本
  StreamSubscription? _transcriptionSubscription;
  Completer<void>? _streamDoneCompleter;
  Timer? _waitingServerStopTimer;
  bool _isFinalizingTranscription = false;
  final ScrollController _textFieldScrollController = ScrollController();

  bool get isPopupVisible => _isPopupVisible;
  bool get isRecording => _recordingState != RecordingState.idle;

  // 超时兜底：防止 start/stop 卡死在 starting/stopping（例如通道调用挂起）
  final Duration _startTimeout = const Duration(seconds: 8);
  final Duration _stopTimeout = const Duration(seconds: 5);
  final Duration _waitingServerStopTimeout = const Duration(seconds: 4);

  // 时间窗口限流：频繁点击不下发到原生，直接提示
  final int _toggleMinIntervalMs = 800;
  int _lastToggleAcceptedAtMs = 0;

  // 提示去抖：避免疯狂点击导致 toast 刷屏
  final int _fastTapToastMinIntervalMs = 800;
  int _lastFastTapToastAtMs = 0;
  bool _toggleInProgress = false;
  double _lastReportedInputHeight = 44;
  bool _inputHeightReportScheduled = false;
  bool _isComposerHovered = false;
  late AnimationController _composerFlowController;

  late Widget _micSvg;
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

    _micSvg = const SizedBox.shrink();
    _sendSvg = const SizedBox.shrink();
    _pauseSvg = const SizedBox.shrink();
    _addSvg = const SizedBox.shrink();
    _commandSvg = const SizedBox.shrink();
    // 进入界面先预取 asr ws token（仅用于 WS 握手）
    _initSpeechRecognition();
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
    _micSvg = SvgPicture.string(
      context.isDarkTheme ? _kLucideMicSvgDark : _kLucideMicSvg,
      width: 24,
      height: 24,
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

  Future<void> _initSpeechRecognition() async {
    try {
      await AsrSpeechRecognitionService.ensureInitialized();
    } catch (e) {
      debugPrint('Failed to init speech recognition: $e');
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
    _transcriptionSubscription?.cancel();
    _waitingServerStopTimer?.cancel();
    _textFieldScrollController.dispose();
    _hasTextNotifier.dispose();
    _isFocusedNotifier.dispose();
    _composerFlowController.dispose();
    widget.controller.removeListener(_onTextChanged);
    widget.focusNode.removeListener(_onFocusChanged);
    super.dispose();
  }

  void _setRecordingState(RecordingState state) {
    if (mounted) {
      setState(() => _recordingState = state);
    } else {
      _recordingState = state;
    }
    widget.onRecordingStateChanged?.call(state);
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
