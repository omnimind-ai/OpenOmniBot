import 'dart:async';
import 'dart:convert';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/image_prewarm_cache_service.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/features/home/pages/common/openclaw_connection_checker.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_project_service.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_context.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';
import 'package:ui/utils/data_parser.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/image/cached_image.dart';

import 'chat_bot_sheet.dart';
import 'widgets/chat_input_area.dart';

class CommandOverlay extends StatefulWidget {
  /// 启动场景参数，目前支持 'summary' 场景
  final String? scene;

  const CommandOverlay({super.key, this.scene});

  @override
  State<CommandOverlay> createState() => _CommandOverlayState();
}

class _CommandOverlayState extends State<CommandOverlay> {
  final TextEditingController _messageController = TextEditingController();
  final FocusNode _inputFocusNode = FocusNode();
  final GlobalKey<ChatInputAreaState> _chatInputAreaKey =
      GlobalKey<ChatInputAreaState>();
  final GlobalKey _inputAreaKey = GlobalKey();

  bool _isPopupVisible = false;
  Map<String, dynamic>? _scheduleInfo;
  int _countdownSeconds = 0;
  double _chatInputAreaHeight = 44;
  bool _openClawEnabled = false;
  String _openClawBaseUrl = '';
  String _openClawToken = '';
  String _openClawUserId = '';
  bool _showSlashCommandPanel = false;
  bool _openClawPanelExpanded = false;
  bool _isChatSheetVisible = false;
  bool _conversationPanelExpanded = false;
  bool _conversationSummariesLoading = false;
  bool _conversationSummariesLoaded = false;
  String? _conversationPanelError;
  List<ConversationModel> _recentConversations = const [];
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;
  bool _annotationMode = false;
  final TextEditingController _openClawBaseUrlController =
      TextEditingController();
  final TextEditingController _openClawTokenController =
      TextEditingController();
  final TextEditingController _openClawUserIdController =
      TextEditingController();
  final GlobalKey _openClawPanelKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    _inputFocusNode.addListener(_onFocusChange);
    _messageController.addListener(_handleSlashCommandInput);
    _onGetScheduleTaskInfo();
    _loadOpenClawConfig();
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          if (!mounted) return;
          if (_conversationPanelExpanded) {
            unawaited(_loadConversationSummaries(force: true));
          } else {
            _conversationSummariesLoaded = false;
          }
        });

    // 预热 Suggestion 图标到内存缓存
    WidgetsBinding.instance.addPostFrameCallback((_) {
      SuggestionImagePrewarmService.prewarm(context, tag: 'CommandOverlay');
    });

    // 如果是总结场景，自动拉起ChatBotSheet
    if (widget.scene == 'summary') {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _showChatSheetWithScene(ChatBotLaunchScene.summary);
      });
    } else if (widget.scene == 'resume_after_auth') {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _showChatSheetWithScene(ChatBotLaunchScene.resumeAfterAuth);
      });
    }
  }

  Future<void> _loadOpenClawConfig() async {
    try {
      final enabled =
          StorageService.getBool(kOpenClawEnabledKey, defaultValue: false) ??
          false;
      final baseUrl =
          StorageService.getString(kOpenClawBaseUrlKey, defaultValue: '') ?? '';
      final token =
          StorageService.getString(kOpenClawTokenKey, defaultValue: '') ?? '';
      final userId =
          StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
      final effectiveEnabled = enabled && baseUrl.trim().isNotEmpty;
      if (enabled && !effectiveEnabled) {
        await StorageService.setBool(kOpenClawEnabledKey, false);
      }
      if (!mounted) return;
      setState(() {
        _openClawEnabled = effectiveEnabled;
        _openClawBaseUrl = baseUrl;
        _openClawToken = token;
        _openClawUserId = userId;
      });
      await _ensureOpenClawUserId();
    } catch (e) {
      debugPrint('加载OpenClaw配置失败: $e');
    }
  }

  Future<void> _ensureOpenClawUserId() async {
    if (_openClawUserId.isNotEmpty) return;
    final existing =
        StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
    if (existing.isNotEmpty) {
      if (!mounted) return;
      setState(() => _openClawUserId = existing);
      return;
    }
    final generated = DateTime.now().microsecondsSinceEpoch.toString();
    await StorageService.setString(kOpenClawUserIdKey, generated);
    if (!mounted) return;
    setState(() => _openClawUserId = generated);
  }

  Future<void> _setOpenClawEnabled(bool enabled) async {
    if (enabled && _openClawBaseUrl.trim().isEmpty) {
      AppToast.show(AppTextLocalizer.text('请先使用 /openclaw 配置 OpenClaw'));
      _showOpenClawCommandPanel(expand: true);
      return;
    }
    if (!mounted) return;
    setState(() => _openClawEnabled = enabled);
    await StorageService.setBool(kOpenClawEnabledKey, enabled);
  }

  // ignore: unused_element
  Future<void> _showOpenClawConfigDialog() async {
    final result = await showDialog<_OpenClawConfigDraft>(
      context: context,
      useRootNavigator: false,
      builder: (_) => _OpenClawConfigDialog(
        initialBaseUrl: _openClawBaseUrl,
        initialToken: _openClawToken,
        initialUserId: _openClawUserId,
      ),
    );
    if (!mounted || result == null) return;
    final baseUrl = result.baseUrl.trim();
    final token = result.token.trim();
    final userId = result.userId.trim();
    await StorageService.setString(kOpenClawBaseUrlKey, baseUrl);
    await StorageService.setString(kOpenClawTokenKey, token);
    if (userId.isNotEmpty) {
      await StorageService.setString(kOpenClawUserIdKey, userId);
    }
    if (!mounted) return;
    setState(() {
      _openClawBaseUrl = baseUrl;
      _openClawToken = token;
      _openClawUserId = userId.isNotEmpty ? userId : _openClawUserId;
    });
    await _ensureOpenClawUserId();

    // 配置保存后检查连接
    _checkOpenClawConnection();
  }

  /// 检查 OpenClaw 服务连接状态
  Future<void> _checkOpenClawConnection() async {
    await OpenClawConnectionChecker.checkAndToast(_openClawBaseUrl);
  }

  void _handleSlashCommandInput() {
    final text = _messageController.text.trimLeft();
    final shouldShow = text.startsWith('/');
    if (!mounted) return;
    if (shouldShow != _showSlashCommandPanel) {
      setState(() {
        _showSlashCommandPanel = shouldShow;
        if (!shouldShow) {
          _openClawPanelExpanded = false;
        }
      });
    }
  }

  void _showOpenClawCommandPanel({bool expand = false}) {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = true;
      _openClawPanelExpanded = expand;
      if (expand) {
        _openClawBaseUrlController.text = _openClawBaseUrl;
        _openClawTokenController.text = _openClawToken;
        _openClawUserIdController.text = _openClawUserId;
      }
    });
  }

  void _hideSlashCommandPanel() {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = false;
      _openClawPanelExpanded = false;
    });
  }

  bool _isPointerInside(GlobalKey key, Offset position) {
    final context = key.currentContext;
    if (context == null) return false;
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) return false;
    final offset = renderBox.localToGlobal(Offset.zero);
    final rect = offset & renderBox.size;
    return rect.contains(position);
  }

  Future<void> _handleOutsideTap(Offset position) async {
    if (!_showSlashCommandPanel && !_openClawPanelExpanded) return;
    if (_isPointerInside(_openClawPanelKey, position) ||
        _isPointerInside(_inputAreaKey, position)) {
      return;
    }
    if (_openClawPanelExpanded) {
      await _applyOpenClawConfig(
        baseUrl: _openClawBaseUrlController.text.trim(),
        token: _openClawTokenController.text.trim(),
        userId: _openClawUserIdController.text.trim(),
        enable: _openClawEnabled,
      );
      _checkOpenClawConnection();
    }
    _hideSlashCommandPanel();
  }

  Future<void> _applyOpenClawConfig({
    required String baseUrl,
    required String token,
    String? userId,
    bool enable = true,
  }) async {
    await StorageService.setString(kOpenClawBaseUrlKey, baseUrl);
    await StorageService.setString(kOpenClawTokenKey, token);
    if (userId != null && userId.isNotEmpty) {
      await StorageService.setString(kOpenClawUserIdKey, userId);
    }
    if (!mounted) return;
    setState(() {
      _openClawBaseUrl = baseUrl;
      _openClawToken = token;
      if (userId != null && userId.isNotEmpty) {
        _openClawUserId = userId;
      }
      _openClawEnabled = enable && baseUrl.trim().isNotEmpty;
    });
    await StorageService.setBool(kOpenClawEnabledKey, _openClawEnabled);
    await _ensureOpenClawUserId();
  }

  Future<bool> _tryHandleSlashCommand(String messageText) async {
    final trimmed = messageText.trim();
    if (!trimmed.startsWith('/')) return false;

    // 只拦截 /openclaw 本地配置命令，其他斜杠命令（如 /model、/help 等）
    // 透传给 OpenClaw 网关或作为普通消息发送
    if (!trimmed.startsWith('/openclaw')) {
      return false;
    }

    final parts = trimmed.split(RegExp(r'\\s+'));
    if (parts.length < 2) {
      AppToast.show('格式: /openclaw <baseurl> --token <token> <userid>');
      return true;
    }

    final baseUrl = parts[1];
    final tokenIndex = parts.indexOf('--token');
    if (tokenIndex == -1) {
      AppToast.show('请在命令中显式包含 --token');
      return true;
    }
    String token = '';
    String? userId;
    if (tokenIndex + 1 < parts.length) {
      token = parts[tokenIndex + 1];
    }
    if (token == '-' || token == 'null') {
      token = '';
    }
    if (tokenIndex + 2 < parts.length) {
      userId = parts[tokenIndex + 2];
    }

    if (baseUrl.trim().isEmpty) {
      AppToast.show('OpenClaw baseurl 不能为空');
      return true;
    }

    await _applyOpenClawConfig(
      baseUrl: baseUrl.trim(),
      token: token.trim(),
      userId: userId?.trim(),
      enable: true,
    );
    _messageController.clear();
    _inputFocusNode.unfocus();
    _hideSlashCommandPanel();
    AppToast.show('OpenClaw 已配置并启用');
    return true;
  }

  Future<void> _onGetScheduleTaskInfo() async {
    final result = await AssistsMessageService.getScheduleTaskInfo();
    debugPrint('<<< getScheduleTaskInfo 返回: $result');

    if (result != null && mounted) {
      final hasScheduleTask = result['hasScheduleTask'] as bool? ?? false;
      if (hasScheduleTask) {
        setState(() {
          _scheduleInfo = result;
        });
        // 计算并启动倒计时
        await _calculateRemainingTime();
        _startCountdown();
      }
    }
  }

  Timer? _countdownTimer;

  Future<void> _calculateRemainingTime() async {
    if (_scheduleInfo == null) return;

    final startTimeStamp = _scheduleInfo!['startTimeStamp'] as int? ?? 0;
    final delayTimes = _scheduleInfo!['delayTimes'] as int? ?? 0;

    if (startTimeStamp > 0 && delayTimes > 0) {
      final currentNanoTime = await AssistsMessageService.getNanoTime();
      if (currentNanoTime != null) {
        final elapsedMs = currentNanoTime - startTimeStamp;
        final remainingMs = (delayTimes * 1000) - elapsedMs;
        _countdownSeconds = remainingMs > 0 ? (remainingMs / 1000).ceil() : 0;
      } else {
        _countdownSeconds = 0;
      }
    } else {
      _countdownSeconds = 0;
    }
    if (mounted) {
      setState(() {});
    }
  }

  void _startCountdown() {
    _countdownTimer?.cancel();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_countdownSeconds > 0 && mounted) {
        setState(() {
          _countdownSeconds--;
        });
      } else {
        timer.cancel();
      }
    });
  }

  String _formatCountdown() {
    final minutes = _countdownSeconds ~/ 60;
    final seconds = _countdownSeconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _inputFocusNode.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    _conversationListChangedSubscription?.cancel();
    super.dispose();
  }

  void _onFocusChange() {}

  Future<void> _dismissFloatingOverlay() async {
    _inputFocusNode.unfocus();
    final dismissed = await AppStateService.dismissFloatingOverlay();
    if (!dismissed) {
      await ScreenDialogService.closeChatBotDialog();
    }
  }

  void _closePage() {
    unawaited(_dismissFloatingOverlay());
  }

  Future<void> _toggleConversationPanel() async {
    final shouldExpand = !_conversationPanelExpanded;
    setState(() {
      _conversationPanelExpanded = shouldExpand;
    });
    if (shouldExpand) {
      await _loadConversationSummaries();
    }
  }

  Future<void> _loadConversationSummaries({bool force = false}) async {
    if (_conversationSummariesLoading) return;
    if (_conversationSummariesLoaded && !force) return;
    setState(() {
      _conversationSummariesLoading = true;
      _conversationPanelError = null;
    });
    try {
      final conversations = await ConversationService.getAllConversations();
      if (!mounted) return;
      setState(() {
        _recentConversations = conversations.take(5).toList(growable: false);
        _conversationSummariesLoaded = true;
        _conversationSummariesLoading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _conversationPanelError = AppTextLocalizer.choose(en: 'Failed to load conversations', zh: '对话加载失败');
        _conversationSummariesLoading = false;
      });
      debugPrint('加载悬浮窗对话摘要失败: $error');
    }
  }

  Future<void> _manageConversation(ConversationModel conversation) async {
    _inputFocusNode.unfocus();
    final opened = await AppStateService.navigateBackToChat(
      conversationId: conversation.id,
      mode: conversation.mode,
    );
    if (!opened) {
      AppToast.show(
        AppTextLocalizer.choose(en: 'Failed to open conversation', zh: '无法打开对话'),
      );
    } else {
      unawaited(AppStateService.dismissFloatingOverlay());
    }
  }

  String _conversationTitle(ConversationModel conversation) {
    final title = conversation.title.trim();
    if (title.isNotEmpty) return title;
    return AppTextLocalizer.choose(en: 'New conversation', zh: '新对话');
  }

  String _conversationSummary(ConversationModel conversation) {
    final summary = conversation.summary?.trim();
    if (summary != null && summary.isNotEmpty) return summary;
    final contextSummary = conversation.contextSummary?.trim();
    if (contextSummary != null && contextSummary.isNotEmpty) {
      return contextSummary;
    }
    final lastMessage = conversation.lastMessage?.trim();
    if (lastMessage != null && lastMessage.isNotEmpty) return lastMessage;
    return AppTextLocalizer.choose(en: 'No summary yet', zh: '暂无摘要');
  }

  String _conversationMeta(ConversationModel conversation) {
    final count = AppTextLocalizer.choose(en: '${conversation.messageCount} messages', zh: '${conversation.messageCount} 条消息');
    return '${conversation.mode.displayLabel} · ${conversation.timeDisplay} · $count';
  }

  Future<void> _sendMessage() async {
    final text = _messageController.text.trim();
    if (text.isEmpty) return;

    final handledSlash = await _tryHandleSlashCommand(text);
    if (handledSlash) return;

    _inputFocusNode.unfocus();
    _messageController.clear();

    _showChatSheet(initialMessage: text);
  }

  void _showChatSheet({String? initialMessage}) {
    _showChatSheetWithScene(
      ChatBotLaunchScene.normal,
      initialMessage: initialMessage,
    );
  }

  /// 显示ChatBotSheet，支持指定启动场景
  void _showChatSheetWithScene(
    ChatBotLaunchScene launchScene, {
    String? initialMessage,
    String? initialDisplayMessage,
    List<Map<String, dynamic>> initialAttachments = const [],
  }) {
    if (_isChatSheetVisible) return;
    if (mounted) {
      setState(() => _isChatSheetVisible = true);
    }
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0),
      // 禁用 showModalBottomSheet 的默认拖动关闭行为
      // 防止向下拖动内容时整个 sheet 跟着移动
      enableDrag: false,
      builder: (context) => ChatBotSheet(
        initialMessage: initialMessage,
        initialDisplayMessage: initialDisplayMessage,
        initialAttachments: initialAttachments,
        launchScene: launchScene,
        openClawEnabled: _openClawEnabled,
      ),
    ).then((_) {
      if (mounted) {
        setState(() => _isChatSheetVisible = false);
        if (_conversationPanelExpanded) {
          unawaited(_loadConversationSummaries(force: true));
        } else {
          _conversationSummariesLoaded = false;
        }
      }
      unawaited(_dismissFloatingOverlay());
    });
  }

  void _onCancelTask() {}

  void _onPopupVisibilityChanged(bool visible) {
    setState(() {
      _isPopupVisible = visible;
    });
  }

  void _onInputHeightChanged(double height) {
    if (_chatInputAreaHeight == height) return;
    setState(() {
      _chatInputAreaHeight = height;
    });
  }

  void _toggleAnnotationMode() {
    _inputFocusNode.unfocus();
    _hideSlashCommandPanel();
    if (!mounted) return;
    setState(() {
      _annotationMode = !_annotationMode;
      if (_annotationMode) {
        _isPopupVisible = false;
      }
    });
  }

  Future<Map<String, Object?>> _buildFloatingAnnotationContext(
    WorkbenchAnnotationPayload payload,
    String prompt,
  ) async {
    try {
      final backend = NativeWorkbenchProjectBackend();
      final project = await backend.getActiveProject();
      if (project != null) {
        final activeFrontendContext = await backend.getActiveFrontendContext();
        final display = _displayForActiveFrontendContext(
          project,
          activeFrontendContext,
        );
        return {
          ...buildWorkbenchAnnotationFrontendContext(
            project: project,
            display: display,
            payload: payload,
            prompt: prompt,
            source: 'xiaowan_floating_annotation_canvas',
          ),
          if (activeFrontendContext != null)
            'activeFlutterContext': activeFrontendContext,
          'screenshotSummary':
              'VLM should inspect the current screen screenshot together with drawingPaths. The Flutter client does not classify the shape or target UI.',
        };
      }
    } catch (error) {
      debugPrint('读取激活 Workbench Project 失败: $error');
    }
    return {
      ...payload.toFrontendContext(
        projectId: '',
        displayId: 'current-screen',
        route: 'current_screen',
        source: 'xiaowan_floating_annotation_canvas',
        visibleState: const {
          'origin': 'xiaowan_floating_window',
          'activeProjectAvailable': false,
        },
      ),
      'screenshotSummary':
          'VLM should inspect the current screen screenshot together with drawingPaths. The Flutter client does not classify the shape or target UI.',
    };
  }

  WorkbenchDisplaySpec _displayForActiveFrontendContext(
    WorkbenchProject project,
    Map<String, dynamic>? activeFrontendContext,
  ) {
    final contextProjectId = activeFrontendContext?['projectId']
        ?.toString()
        .trim();
    final contextDisplayId = activeFrontendContext?['displayId']
        ?.toString()
        .trim();
    if (contextProjectId == project.projectId &&
        contextDisplayId != null &&
        contextDisplayId.isNotEmpty) {
      for (final display in project.displays) {
        if (display.id == contextDisplayId) {
          return display;
        }
      }
    }
    return project.primaryDisplay;
  }

  String _buildAnnotationAgentMessage(
    String prompt,
    Map<String, Object?> frontendContext,
  ) {
    const encoder = JsonEncoder.withIndent('  ');
    final contextJson = encoder.convert(frontendContext);
    return '''
$prompt

小万悬浮窗画布上下文如下。已附上“当前屏幕 + 红色笔迹”的合成截图，请用 VLM 直接看图判断用户标注的形状和目标 UI，不要在前端做形状识别。若这是 Workbench Project 前端迭代，请把下面 JSON 原样作为 frontendContext 调用 workbench_project_hot_update。

```json
$contextJson
```
''';
  }

  List<Map<String, dynamic>> _drawingPathsForNative(
    WorkbenchAnnotationPayload payload,
  ) {
    return payload.strokes
        .map(
          (stroke) =>
              Map<String, dynamic>.from(stroke.toMap(payload.canvasSize)),
        )
        .toList(growable: false);
  }

  Future<Map<String, dynamic>?> _captureAnnotationAttachment(
    WorkbenchAnnotationPayload payload,
  ) async {
    if (payload.canvasSize.width <= 0 || payload.canvasSize.height <= 0) {
      return null;
    }
    return AssistsMessageService.captureWorkbenchAnnotationAttachment(
      canvasWidth: payload.canvasSize.width,
      canvasHeight: payload.canvasSize.height,
      drawingPaths: _drawingPathsForNative(payload),
    );
  }

  Map<String, Object?> _annotationImageContext(
    WorkbenchAnnotationPayload payload,
    Map<String, dynamic> attachment,
  ) {
    final width = attachment['width'];
    final height = attachment['height'];
    final imageWidth = width is num ? width.toDouble() : 0.0;
    final imageHeight = height is num ? height.toDouble() : 0.0;
    return {
      'attachmentId': attachment['id']?.toString(),
      'name': attachment['name']?.toString(),
      'mimeType': attachment['mimeType']?.toString(),
      'path': attachment['path']?.toString(),
      'uri': attachment['uri']?.toString(),
      'width': width,
      'height': height,
      'coordinateSpace': 'screenshot_pixels',
      'canvasToImageScale': {
        'x': payload.canvasSize.width <= 0
            ? 1
            : imageWidth / payload.canvasSize.width,
        'y': payload.canvasSize.height <= 0
            ? 1
            : imageHeight / payload.canvasSize.height,
      },
    };
  }

  Future<bool> _submitAnnotation(
    WorkbenchAnnotationPayload payload,
    String prompt,
  ) async {
    final baseFrontendContext = await _buildFloatingAnnotationContext(
      payload,
      prompt,
    );
    final annotationAttachment = await _captureAnnotationAttachment(payload);
    if (!mounted) return false;
    final frontendContext = <String, Object?>{
      ...baseFrontendContext,
      if (annotationAttachment != null)
        'annotationImage': _annotationImageContext(
          payload,
          annotationAttachment,
        ),
      'screenshotSummary': annotationAttachment == null
          ? 'Screenshot capture failed; VLM should fall back to current screen state and drawingPaths. The Flutter client does not classify the shape or target UI.'
          : 'An attached image contains the current screen screenshot composited with the user red strokes. VLM should infer shape and target UI from that image plus drawingPaths.',
    };
    if (annotationAttachment == null) {
      AppToast.show(
        AppTextLocalizer.choose(en: 'Screenshot capture failed; sending red strokes as fallback.', zh: '截图合成失败，先用红线坐标兜底发送。'),
      );
    }
    if (!mounted) return false;
    final agentMessage = _buildAnnotationAgentMessage(prompt, frontendContext);
    setState(() => _annotationMode = false);
    _showChatSheetWithScene(
      ChatBotLaunchScene.normal,
      initialMessage: agentMessage,
      initialDisplayMessage: prompt,
      initialAttachments: annotationAttachment == null
          ? const []
          : [annotationAttachment],
    );
    return true;
  }

  Widget _buildSlashCommandPanel() {
    final visible = _showSlashCommandPanel || _openClawPanelExpanded;
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      transitionBuilder: (child, animation) {
        final slide = Tween<Offset>(
          begin: const Offset(0, 0.15),
          end: Offset.zero,
        ).animate(animation);
        return ClipRect(
          child: SlideTransition(
            position: slide,
            child: FadeTransition(opacity: animation, child: child),
          ),
        );
      },
      child: !visible
          ? const SizedBox.shrink()
          : Container(
              key: _openClawPanelKey,
              margin: const EdgeInsets.fromLTRB(24, 0, 24, 6),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.08),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: _openClawPanelExpanded
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'OpenClaw 配置',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: Color(0xFF1F2937),
                          ),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _openClawBaseUrlController,
                          decoration: const InputDecoration(
                            labelText: 'Base URL',
                            hintText: 'http://192.168.1.10:18789',
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextField(
                          controller: _openClawTokenController,
                          decoration: const InputDecoration(
                            labelText: 'Token（可选）',
                            hintText: '为空表示无需 token',
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextField(
                          controller: _openClawUserIdController,
                          decoration: const InputDecoration(
                            labelText: 'User ID（可选）',
                            isDense: true,
                          ),
                        ),
                      ],
                    )
                  : InkWell(
                      onTap: () {
                        _showOpenClawCommandPanel(expand: true);
                      },
                      borderRadius: BorderRadius.circular(10),
                      child: Row(
                        children: const [
                          Icon(Icons.link, size: 16, color: Color(0xFF2563EB)),
                          SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              'OpenClaw',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                                color: Color(0xFF1F2937),
                              ),
                            ),
                          ),
                          Text(
                            '配置',
                            style: TextStyle(
                              fontSize: 12,
                              color: Color(0xFF6B7280),
                            ),
                          ),
                        ],
                      ),
                    ),
            ),
    );
  }

  Widget _buildConversationOverlayPanel() {
    final screenWidth = MediaQuery.of(context).size.width;
    final topInset = MediaQuery.of(context).padding.top;
    final maxPanelWidth = (screenWidth - 32).clamp(280.0, 420.0).toDouble();
    return Positioned(
      top: topInset + 12,
      right: 16,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxPanelWidth),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(18),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
            child: Material(
              color: Colors.transparent,
              child: AnimatedSize(
                duration: const Duration(milliseconds: 180),
                curve: Curves.easeOutCubic,
                alignment: Alignment.topRight,
                child: _conversationPanelExpanded
                    ? _buildExpandedConversationPanel(maxPanelWidth)
                    : _buildCollapsedConversationPanel(),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildPanelIconButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback onPressed,
  }) {
    return Tooltip(
      message: tooltip,
      child: IconButton(
        visualDensity: VisualDensity.compact,
        constraints: const BoxConstraints.tightFor(width: 36, height: 36),
        padding: EdgeInsets.zero,
        icon: Icon(icon, size: 18, color: Colors.white),
        onPressed: onPressed,
      ),
    );
  }

  Widget _buildCollapsedConversationPanel() {
    return Container(
      padding: const EdgeInsets.only(left: 12),
      decoration: BoxDecoration(
        color: const Color(0xCC273142),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Colors.white.withValues(alpha: 0.14)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.16),
            blurRadius: 16,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.forum_outlined, size: 16, color: Colors.white),
          const SizedBox(width: 6),
          Text(
            AppTextLocalizer.choose(en: 'Conversations', zh: '对话'),
            style: const TextStyle(
              color: Colors.white,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
          _buildPanelIconButton(
            icon: Icons.expand_more,
            tooltip: AppTextLocalizer.choose(en: 'Show summary', zh: '展开摘要'),
            onPressed: () => unawaited(_toggleConversationPanel()),
          ),
          _buildPanelIconButton(
            icon: Icons.close,
            tooltip: AppTextLocalizer.choose(en: 'Close', zh: '关闭'),
            onPressed: _closePage,
          ),
        ],
      ),
    );
  }

  Widget _buildExpandedConversationPanel(double width) {
    return Container(
      width: width,
      padding: const EdgeInsets.fromLTRB(14, 10, 10, 12),
      decoration: BoxDecoration(
        color: const Color(0xE62A3142),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Colors.white.withValues(alpha: 0.14)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.18),
            blurRadius: 20,
            offset: const Offset(0, 10),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.forum_outlined, size: 17, color: Colors.white),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  AppTextLocalizer.choose(en: 'Conversation summaries', zh: '对话摘要'),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              _buildPanelIconButton(
                icon: Icons.refresh,
                tooltip: AppTextLocalizer.choose(en: 'Refresh', zh: '刷新'),
                onPressed: () =>
                    unawaited(_loadConversationSummaries(force: true)),
              ),
              _buildPanelIconButton(
                icon: Icons.expand_less,
                tooltip: AppTextLocalizer.choose(en: 'Collapse', zh: '收起'),
                onPressed: () => setState(() {
                  _conversationPanelExpanded = false;
                }),
              ),
              _buildPanelIconButton(
                icon: Icons.close,
                tooltip: AppTextLocalizer.choose(en: 'Close', zh: '关闭'),
                onPressed: _closePage,
              ),
            ],
          ),
          const SizedBox(height: 8),
          _buildConversationPanelBody(),
        ],
      ),
    );
  }

  Widget _buildConversationPanelBody() {
    if (_conversationSummariesLoading) {
      return const SizedBox(
        height: 88,
        child: Center(
          child: SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: Colors.white,
            ),
          ),
        ),
      );
    }

    final error = _conversationPanelError;
    if (error != null) {
      return _buildConversationPanelMessage(error);
    }

    if (_recentConversations.isEmpty) {
      return _buildConversationPanelMessage(
        AppTextLocalizer.choose(en: 'No conversations yet', zh: '暂无对话'),
      );
    }

    return ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 280),
      child: ListView.separated(
        padding: EdgeInsets.zero,
        shrinkWrap: true,
        physics: const ClampingScrollPhysics(),
        itemCount: _recentConversations.length,
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, index) {
          return _buildConversationSummaryItem(_recentConversations[index]);
        },
      ),
    );
  }

  Widget _buildConversationPanelMessage(String message) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 18),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        message,
        textAlign: TextAlign.center,
        style: TextStyle(
          color: Colors.white.withValues(alpha: 0.82),
          fontSize: 13,
        ),
      ),
    );
  }

  Widget _buildConversationSummaryItem(ConversationModel conversation) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () => unawaited(_manageConversation(conversation)),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    _conversationTitle(conversation),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  conversation.timeDisplay,
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.58),
                    fontSize: 11,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              _conversationSummary(conversation),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.78),
                fontSize: 12,
                height: 1.35,
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    _conversationMeta(conversation),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.52),
                      fontSize: 11,
                    ),
                  ),
                ),
                TextButton.icon(
                  onPressed: () => unawaited(_manageConversation(conversation)),
                  icon: const Icon(Icons.open_in_new, size: 14),
                  label: Text(AppTextLocalizer.choose(en: 'Manage', zh: '管理')),
                  style: TextButton.styleFrom(
                    foregroundColor: Colors.white,
                    visualDensity: VisualDensity.compact,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    minimumSize: const Size(0, 30),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final keyboardHeight = MediaQuery.of(context).viewInsets.bottom;
    final bottomPadding = keyboardHeight + 20;
    const double inputHeaderOffset = 0;

    final showSlashPanel = _showSlashCommandPanel || _openClawPanelExpanded;
    final annotationToolbarBottom =
        bottomPadding + _chatInputAreaHeight + inputHeaderOffset + 8;
    final content = Stack(
      children: [
        // 快捷提示气泡 - 随键盘移动
        Positioned(
          left: 24,
          right: 24,
          bottom: bottomPadding + _chatInputAreaHeight + inputHeaderOffset,
          child: IgnorePointer(
            ignoring: showSlashPanel,
            child: AnimatedOpacity(
              opacity: showSlashPanel ? 0.0 : 1.0,
              duration: const Duration(milliseconds: 150),
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [],
              ),
            ),
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: bottomPadding + _chatInputAreaHeight + inputHeaderOffset,
          child: _buildSlashCommandPanel(),
        ),
        if (!_annotationMode && !_isChatSheetVisible)
          _buildConversationOverlayPanel(),
        // 底部输入框区域
        Positioned(
          left: 0,
          right: 0,
          bottom: bottomPadding,
          child: Container(
            key: _inputAreaKey,
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ChatInputArea(
                  key: _chatInputAreaKey,
                  controller: _messageController,
                  focusNode: _inputFocusNode,
                  isProcessing: false,
                  onSendMessage: _sendMessage,
                  onCancelTask: _onCancelTask,
                  onPopupVisibilityChanged: _onPopupVisibilityChanged,

                  onInputHeightChanged: _onInputHeightChanged,
                  openClawEnabled: _openClawEnabled,
                  onToggleOpenClaw: _setOpenClawEnabled,
                  onLongPressOpenClaw: () =>
                      _showOpenClawCommandPanel(expand: true),
                  annotationEnabled: _annotationMode,
                  onToggleAnnotation: _toggleAnnotationMode,
                  useFrostedGlass: true, // command_overlay 使用毛玻璃效果
                ),
              ],
            ),
          ),
        ),
        if (_isPopupVisible)
          Positioned(
            right: 24,
            bottom: bottomPadding + 52 + inputHeaderOffset,
            child:
                _chatInputAreaKey.currentState?.buildPopupMenu() ??
                const SizedBox.shrink(),
          ),
        if (_scheduleInfo != null &&
            (_scheduleInfo!['scheduleStatus'] == 'SCHEDULED' ||
                _scheduleInfo!['scheduleStatus'] == 'FAILED'))
          Positioned(
            right: 24,
            bottom: bottomPadding + 52 + inputHeaderOffset,
            child: _buildScheduleBubble(),
          ),
      ],
    );
    final bodyChild = _annotationMode
        ? WorkbenchAnnotationOverlay(
            toolbarBottomInset: annotationToolbarBottom,
            onClose: _toggleAnnotationMode,
            onSubmit: _submitAnnotation,
            child: content,
          )
        : content;
    return Scaffold(
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      body: Listener(
        behavior: HitTestBehavior.deferToChild,
        onPointerDown: (event) => _handleOutsideTap(event.position),
        child: bodyChild,
      ),
    );
  }

  /// 点击预约气泡后显示预约卡片
  void _showScheduleSheet() {
    if (_isChatSheetVisible) return;
    final wasFailedOnEnter = _scheduleInfo?['scheduleStatus'] == 'FAILED';
    if (mounted) {
      setState(() => _isChatSheetVisible = true);
    }
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0),
      // 禁用 showModalBottomSheet 的默认拖动关闭行为
      enableDrag: false,
      builder: (context) => ChatBotSheet(
        initialScheduleInfo: _scheduleInfo,
        openClawEnabled: _openClawEnabled,
      ),
    ).then((_) {
      if (mounted) {
        setState(() => _isChatSheetVisible = false);
      }
      unawaited(_dismissFloatingOverlay());
      if (wasFailedOnEnter) {
        AssistsMessageService.clearScheduleTask();
      }
    });
  }

  Widget _buildScheduleBubble() {
    final extraJsonStr =
        safeDecodeMap(_scheduleInfo?['taskParamsJson'])['extraJson'] ?? '';
    final extraJson = safeDecodeMap(extraJsonStr);
    final taskIconUrl = extraJson['taskIconUrl'] as String? ?? '';
    final scheduleStatus = _scheduleInfo?['scheduleStatus'] as String? ?? '';
    final bool isFailed = scheduleStatus == 'FAILED';
    final String statusText;
    if (isFailed) {
      statusText = '任务失败';
    } else if (_countdownSeconds <= 0) {
      statusText = '正在执行';
    } else {
      statusText = '${_formatCountdown()} 后下单';
    }

    return GestureDetector(
      onTap: _showScheduleSheet,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(21),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
          child: Container(
            height: 26,
            padding: const EdgeInsets.fromLTRB(6, 4, 12, 4),
            decoration: ShapeDecoration(
              color: const Color(0x99353E53), // rgba(53,62,83,0.6)
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(21),
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.start,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                if (taskIconUrl.isNotEmpty) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(99),
                    child: CachedImage(
                      imageUrl: taskIconUrl,
                      width: 16,
                      height: 16,
                      fit: BoxFit.cover,
                    ),
                  ),
                  const SizedBox(width: 8),
                ],
                Text(
                  statusText,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontFamily: 'PingFang SC',
                    fontWeight: FontWeight.w400,
                    height: 1.50,
                    letterSpacing: 0.333,
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

class _OpenClawConfigDraft {
  const _OpenClawConfigDraft({
    required this.baseUrl,
    required this.token,
    required this.userId,
  });

  final String baseUrl;
  final String token;
  final String userId;
}

class _OpenClawConfigDialog extends StatefulWidget {
  const _OpenClawConfigDialog({
    required this.initialBaseUrl,
    required this.initialToken,
    required this.initialUserId,
  });

  final String initialBaseUrl;
  final String initialToken;
  final String initialUserId;

  @override
  State<_OpenClawConfigDialog> createState() => _OpenClawConfigDialogState();
}

class _OpenClawConfigDialogState extends State<_OpenClawConfigDialog> {
  late final TextEditingController _baseUrlController;
  late final TextEditingController _tokenController;
  late final TextEditingController _userIdController;
  final FocusNode _baseUrlFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _baseUrlController = TextEditingController(text: widget.initialBaseUrl);
    _tokenController = TextEditingController(text: widget.initialToken);
    _userIdController = TextEditingController(text: widget.initialUserId);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _baseUrlFocusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _baseUrlFocusNode.dispose();
    _baseUrlController.dispose();
    _tokenController.dispose();
    _userIdController.dispose();
    super.dispose();
  }

  void _close([_OpenClawConfigDraft? value]) {
    FocusScope.of(context).unfocus();
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
        title: const Text('OpenClaw 配置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _baseUrlController,
              focusNode: _baseUrlFocusNode,
              decoration: const InputDecoration(
                labelText: 'Base URL',
                hintText: 'http://192.168.1.10:18789',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _tokenController,
              decoration: const InputDecoration(labelText: 'Token（可选）'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _userIdController,
              decoration: const InputDecoration(labelText: 'User ID（可选）'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => _close(), child: const Text('取消')),
          ElevatedButton(
            onPressed: () => _close(
              _OpenClawConfigDraft(
                baseUrl: _baseUrlController.text,
                token: _tokenController.text,
                userId: _userIdController.text,
              ),
            ),
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }
}
