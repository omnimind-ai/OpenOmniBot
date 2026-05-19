import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/local_model/local_model_feature.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/mcp_server_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/workspace_memory_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class ConfigurationGuidePage extends StatefulWidget {
  const ConfigurationGuidePage({super.key, this.replay = false});

  final bool replay;

  @override
  State<ConfigurationGuidePage> createState() => _ConfigurationGuidePageState();
}

class _ConfigurationGuidePageState extends State<ConfigurationGuidePage>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  late final PageController _pageController;
  late final AnimationController _entranceController;
  late final Animation<double> _entranceOpacity;
  late final Animation<Offset> _entranceOffset;
  int _currentIndex = 0;
  bool _finishing = false;
  bool _loadingSnapshot = true;
  _GuideSnapshot _snapshot = _GuideSnapshot.empty();

  static const Color _warmAccent = Color(0xFFD4AF37);
  static const Color _greenAccent = Color(0xFF27A376);
  static const Color _violetAccent = Color(0xFF7C5CFF);
  static const Color _orangeAccent = Color(0xFFFF8A3D);
  static const Color _tealAccent = Color(0xFF12A7A2);
  static const Color _roseAccent = Color(0xFFE8647A);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _pageController = PageController();
    _entranceController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 560),
    );
    _entranceOpacity = CurvedAnimation(
      parent: _entranceController,
      curve: Curves.easeOut,
    );
    _entranceOffset =
        Tween<Offset>(begin: const Offset(0, 0.035), end: Offset.zero).animate(
          CurvedAnimation(
            parent: _entranceController,
            curve: Curves.easeOutCubic,
          ),
        );
    _entranceController.forward();
    unawaited(_refreshSnapshot());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pageController.dispose();
    _entranceController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refreshSnapshot());
    }
  }

  Future<void> _refreshSnapshot() async {
    try {
      final next = await _GuideSnapshot.load();
      if (!mounted) return;
      setState(() {
        _snapshot = next;
        _loadingSnapshot = false;
      });
    } catch (e) {
      debugPrint('ConfigurationGuidePage refresh snapshot failed: $e');
      if (!mounted) return;
      setState(() {
        _loadingSnapshot = false;
      });
    }
  }

  String _t({required String zh, required String en}) {
    return Localizations.localeOf(context).languageCode == 'en' ? en : zh;
  }

  Future<void> _openRoute(String route) async {
    await GoRouterManager.pushForResult<void>(
      route,
      queryParams: const {'fromOnboarding': 'true'},
    );
    if (mounted) {
      unawaited(_refreshSnapshot());
    }
  }

  Future<void> _openCompanionSettings() async {
    try {
      final granted = await ensureInstalledAppsPermission();
      if (granted == true) {
        await _openRoute('/home/companion_setting');
      } else {
        unawaited(_refreshSnapshot());
      }
    } catch (e) {
      debugPrint('Open companion settings from guide failed: $e');
      if (!mounted) return;
      showToast(
        context.l10n.settingsInstalledAppsPermissionFailed,
        type: ToastType.error,
      );
    }
  }

  Future<void> _enableLocalService() async {
    try {
      final info = await McpServerService.setEnabled(true);
      if (!mounted) return;
      showToast(
        info?.endpoint.isNotEmpty == true
            ? context.l10n.settingsMcpEnabledToast(info!.endpoint)
            : context.l10n.settingsMcpEnabledToast(''),
        type: ToastType.success,
      );
      await _refreshSnapshot();
    } catch (e) {
      debugPrint('Enable MCP local service from guide failed: $e');
      if (!mounted) return;
      showToast(context.l10n.settingsMcpToggleFailed, type: ToastType.error);
    }
  }

  Future<void> _finishGuide() async {
    if (_finishing) return;
    setState(() {
      _finishing = true;
    });
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    if (!mounted) return;

    if (widget.replay && GoRouterManager.canPop()) {
      GoRouterManager.pop(true);
      return;
    }
    GoRouterManager.clearAndNavigateTo(GoRouterManager.homeRoute);
  }

  void _goToPage(int index) {
    if (index < 0 || index >= _pages.length) return;
    _pageController.animateToPage(
      index,
      duration: const Duration(milliseconds: 320),
      curve: Curves.easeOutCubic,
    );
  }

  void _nextPage() {
    if (_currentIndex >= _pages.length - 1) {
      unawaited(_finishGuide());
      return;
    }
    _goToPage(_currentIndex + 1);
  }

  List<_GuideStep> get _pages {
    final modelActions = <_GuideAction>[
      _GuideAction(
        icon: Icons.key_rounded,
        title: _t(zh: '接入云端模型', en: 'Connect cloud models'),
        subtitle: _t(
          zh: '配置 OpenAI、Anthropic 或兼容接口，给小万一个可靠的大脑。',
          en: 'Add OpenAI, Anthropic, or compatible endpoints so Omnibot has a reliable brain.',
        ),
        label: _t(zh: '配置', en: 'Configure'),
        onTap: () => _openRoute('/home/vlm_model_setting'),
        completed: _snapshot.cloudModelConfigured,
      ),
      _GuideAction(
        icon: Icons.tune_rounded,
        title: _t(zh: '按场景分配模型', en: 'Assign scene models'),
        subtitle: _t(
          zh: '为视觉、对话、语音等场景选择更适合的模型。',
          en: 'Choose the best model for vision, chat, voice, and other scenes.',
        ),
        label: _t(zh: '选择', en: 'Choose'),
        onTap: () => _openRoute('/home/scene_model_setting'),
      ),
      if (localModelFeature.enabled)
        _GuideAction(
          icon: Icons.memory_rounded,
          title: _t(zh: '准备本地推理', en: 'Prepare local inference'),
          subtitle: _t(
            zh: '下载推荐模型，离线也能执行一部分 Agent 能力。',
            en: 'Download the recommended model so some agent features work offline.',
          ),
          label: _t(zh: '浏览', en: 'Browse'),
          onTap: () => _openRoute(
            '/home/local_models?tab=market&backend=$kOnboardingRecommendedBackend&pinned=$kOnboardingRecommendedModelId',
          ),
          completed: _snapshot.localModelReady,
        ),
      _GuideAction(
        icon: Icons.cloud_sync_rounded,
        title: _t(zh: '打开 Workspace 记忆', en: 'Enable workspace memory'),
        subtitle: _t(
          zh: '让项目文件、笔记和历史上下文更容易被检索到。',
          en: 'Make project files, notes, and prior context easier to retrieve.',
        ),
        label: _t(zh: '设置', en: 'Set up'),
        onTap: () => _openRoute('/home/workspace_memory_setting'),
        completed: _snapshot.workspaceMemoryConfigured,
      ),
    ];

    return [
      _GuideStep(
        accent: _warmAccent,
        icon: Icons.auto_awesome_rounded,
        eyebrow: _t(zh: '欢迎', en: 'Welcome'),
        title: _t(zh: '先把小万调到顺手。', en: 'Tune Omnibot before the first run.'),
        description: _t(
          zh: '这不是一张设置清单，而是一条最短上手路径。你可以左右滑动、跳过某页，或者稍后再补齐。',
          en: 'This is a short setup path, not a settings checklist. Swipe, skip a page, or finish the rest later.',
        ),
        primaryLabel: _t(zh: '开始引导', en: 'Start guide'),
        primaryIcon: Icons.arrow_forward_rounded,
        onPrimary: _nextPage,
        bullets: [
          _GuideBullet(
            label: _t(zh: '必要权限', en: 'Required access'),
            value: _snapshot.permissionProgressLabel,
            completed: _snapshot.corePermissionsReady,
          ),
          _GuideBullet(
            label: _t(zh: '模型大脑', en: 'Model brain'),
            value: _snapshot.cloudModelConfigured
                ? _t(zh: '已接入', en: 'Connected')
                : _t(zh: '待配置', en: 'Pending'),
            completed: _snapshot.cloudModelConfigured,
          ),
          _GuideBullet(
            label: _t(zh: '体验偏好', en: 'Preferences'),
            value: _t(zh: '已保存', en: 'Saved'),
            completed: true,
          ),
        ],
      ),
      _GuideStep(
        accent: _greenAccent,
        icon: Icons.admin_panel_settings_rounded,
        eyebrow: _t(zh: '第 1 步', en: 'Step 1'),
        title: _t(
          zh: '先给 Agent 可控的行动边界。',
          en: 'Give the agent a clear action boundary.',
        ),
        description: _t(
          zh: '悬浮窗、后台运行、应用列表和无障碍决定小万能否看见任务、保持在线并执行操作。',
          en: 'Overlay, background running, installed apps, and Accessibility decide whether Omnibot can stay present and act.',
        ),
        primaryLabel: _t(zh: '配置核心权限', en: 'Configure access'),
        primaryIcon: Icons.open_in_new_rounded,
        onPrimary: () => _openRoute('/home/authorize_setting'),
        bullets: [
          _GuideBullet(
            label: _t(zh: '核心权限', en: 'Core access'),
            value: _snapshot.permissionProgressLabel,
            completed: _snapshot.corePermissionsReady,
          ),
          _GuideBullet(
            label: _t(zh: '可选高级能力', en: 'Advanced access'),
            value: _snapshot.advancedAccessReady
                ? _t(zh: '可用', en: 'Ready')
                : _t(zh: '按需开启', en: 'Optional'),
            completed: _snapshot.advancedAccessReady,
          ),
        ],
        actions: [
          _GuideAction(
            icon: Icons.security_rounded,
            title: _t(zh: '权限总览', en: 'Access overview'),
            subtitle: _t(
              zh: '集中检查后台运行、悬浮窗、应用列表、无障碍、Shizuku 与文件访问。',
              en: 'Review background, overlay, installed apps, Accessibility, Shizuku, and storage access.',
            ),
            label: _t(zh: '打开', en: 'Open'),
            onTap: () => _openRoute('/home/authorize_setting'),
            completed: _snapshot.corePermissionsReady,
          ),
          _GuideAction(
            icon: Icons.menu_book_rounded,
            title: _t(zh: '查看机型说明', en: 'Device-specific guide'),
            subtitle: _t(
              zh: '不同品牌的电池与自启动入口不一样，这里有更细的步骤。',
              en: 'Battery and autostart entries differ by brand; this page shows the detailed path.',
            ),
            label: _t(zh: '查看', en: 'View'),
            onTap: () => _openRoute('/home/permission_guide'),
          ),
        ],
      ),
      _GuideStep(
        accent: _violetAccent,
        icon: Icons.psychology_alt_rounded,
        eyebrow: _t(zh: '第 2 步', en: 'Step 2'),
        title: _t(
          zh: '选好小万思考时用哪颗大脑。',
          en: 'Choose the brain Omnibot thinks with.',
        ),
        description: _t(
          zh: '云端模型负责通用推理，本地模型负责离线和隐私场景，Workspace 记忆负责长期上下文。',
          en: 'Cloud models handle general reasoning, local models help offline and private work, and workspace memory carries long context.',
        ),
        primaryLabel: _t(zh: '配置模型提供商', en: 'Configure providers'),
        primaryIcon: Icons.open_in_new_rounded,
        onPrimary: () => _openRoute('/home/vlm_model_setting'),
        bullets: [
          _GuideBullet(
            label: _t(zh: '云模型', en: 'Cloud model'),
            value: _snapshot.cloudModelConfigured
                ? _t(zh: '已配置', en: 'Configured')
                : _t(zh: '未配置', en: 'Not set'),
            completed: _snapshot.cloudModelConfigured,
          ),
          if (localModelFeature.enabled)
            _GuideBullet(
              label: _t(zh: '本地模型', en: 'Local model'),
              value: _snapshot.localModelReady
                  ? _t(zh: '已就绪', en: 'Ready')
                  : _t(zh: '可选', en: 'Optional'),
              completed: _snapshot.localModelReady,
            ),
          _GuideBullet(
            label: _t(zh: '记忆检索', en: 'Memory retrieval'),
            value: _snapshot.workspaceMemoryConfigured
                ? _t(zh: '嵌入可用', en: 'Embedding ready')
                : _t(zh: '词法可用', en: 'Lexical ready'),
            completed: true,
          ),
        ],
        actions: modelActions,
      ),
      _GuideStep(
        accent: _orangeAccent,
        icon: Icons.hub_rounded,
        eyebrow: _t(zh: '第 3 步', en: 'Step 3'),
        title: _t(
          zh: '把工具、服务和终端接到同一张桌面。',
          en: 'Bring tools, service, and terminal onto one workbench.',
        ),
        description: _t(
          zh: 'MCP 工具扩展 Agent 的动作，本机服务方便局域网调用，Alpine 环境适合跑轻量命令。',
          en: 'MCP tools extend agent actions, local service exposes LAN access, and Alpine is ready for lightweight commands.',
        ),
        primaryLabel: _snapshot.mcpEnabled
            ? _t(zh: '管理 MCP 工具', en: 'Manage MCP tools')
            : _t(zh: '开启本机服务', en: 'Enable local service'),
        primaryIcon: _snapshot.mcpEnabled
            ? Icons.extension_rounded
            : Icons.power_settings_new_rounded,
        onPrimary: _snapshot.mcpEnabled
            ? () => _openRoute('/home/mcp_tools')
            : _enableLocalService,
        bullets: [
          _GuideBullet(
            label: _t(zh: '本机服务', en: 'Local service'),
            value: _snapshot.mcpEnabled
                ? _t(zh: '已开启', en: 'Enabled')
                : _t(zh: '未开启', en: 'Off'),
            completed: _snapshot.mcpEnabled,
          ),
          _GuideBullet(
            label: _t(zh: 'MCP 工具', en: 'MCP tools'),
            value: _t(zh: '可扩展', en: 'Extensible'),
            completed: true,
          ),
          _GuideBullet(
            label: _t(zh: 'Alpine 环境', en: 'Alpine env'),
            value: _t(zh: '可检查', en: 'Checkable'),
            completed: true,
          ),
        ],
        actions: [
          _GuideAction(
            icon: Icons.extension_rounded,
            title: _t(zh: '添加 MCP 工具', en: 'Add MCP tools'),
            subtitle: _t(
              zh: '连接远端服务，让 Agent 能调用更多专业能力。',
              en: 'Connect remote services so the agent can call more tools.',
            ),
            label: _t(zh: '管理', en: 'Manage'),
            onTap: () => _openRoute('/home/mcp_tools'),
          ),
          _GuideAction(
            icon: Icons.cloud_queue_rounded,
            title: _t(
              zh: '本机 MCP / Webchat 服务',
              en: 'Local MCP / Webchat service',
            ),
            subtitle: _snapshot.mcpEnabled
                ? _t(
                    zh: '服务已开启，可回到设置页查看访问地址与 Token。',
                    en: 'The service is on; open Settings to view address and token.',
                  )
                : _t(
                    zh: '一键开启后，可在同一局域网内访问小万服务。',
                    en: 'Enable it to access Omnibot services on the same local network.',
                  ),
            label: _snapshot.mcpEnabled
                ? _t(zh: '查看', en: 'View')
                : _t(zh: '开启', en: 'Enable'),
            onTap: _snapshot.mcpEnabled
                ? () => _openRoute('/home/settings')
                : _enableLocalService,
            completed: _snapshot.mcpEnabled,
          ),
          _GuideAction(
            icon: Icons.terminal_rounded,
            title: _t(zh: '检查 Alpine 环境', en: 'Check Alpine environment'),
            subtitle: _t(
              zh: '确认内置终端、基础包和网络状态是否可用。',
              en: 'Confirm the embedded terminal, base packages, and network state.',
            ),
            label: _t(zh: '检查', en: 'Check'),
            onTap: () => _openRoute('/home/termux_setting'),
          ),
        ],
      ),
      _GuideStep(
        accent: _tealAccent,
        icon: Icons.tips_and_updates_rounded,
        eyebrow: _t(zh: '第 4 步', en: 'Step 4'),
        title: _t(
          zh: '把日常体验调成你的手感。',
          en: 'Make the daily experience feel yours.',
        ),
        description: _t(
          zh: '外观、首页、闹钟、振动、回车发送、任务完成后返回和文件打开方式，都在这里收束。',
          en: 'Appearance, home greeting, alarms, vibration, Enter behavior, return-to-chat, and open behavior all live here.',
        ),
        primaryLabel: _t(zh: '打开杂项设置', en: 'Open misc settings'),
        primaryIcon: Icons.open_in_new_rounded,
        onPrimary: () => _openRoute('/home/experience_misc_setting'),
        bullets: [
          _GuideBullet(
            label: _t(zh: '振动反馈', en: 'Vibration'),
            value: _snapshot.vibrationEnabled
                ? _t(zh: '已开启', en: 'On')
                : _t(zh: '已关闭', en: 'Off'),
            completed: _snapshot.vibrationEnabled,
          ),
          _GuideBullet(
            label: _t(zh: '任务后返回', en: 'Return after task'),
            value: _snapshot.autoBackToChatEnabled
                ? _t(zh: '回聊天', en: 'To chat')
                : _t(zh: '留当前页', en: 'Stay'),
            completed: true,
          ),
          _GuideBullet(
            label: _t(zh: '发送按钮', en: 'Send button'),
            value: _snapshot.independentSendButtonEnabled
                ? _t(zh: '独立按钮', en: 'Separate')
                : _t(zh: '回车发送', en: 'Enter sends'),
            completed: true,
          ),
        ],
        actions: [
          _GuideAction(
            icon: Icons.wallpaper_rounded,
            title: _t(zh: '外观和背景', en: 'Appearance and background'),
            subtitle: _t(
              zh: '设置主题、语言、背景图、聊天字号和文本颜色。',
              en: 'Set theme, language, shared background, chat size, and text color.',
            ),
            label: _t(zh: '调整', en: 'Adjust'),
            onTap: () => _openRoute('/home/background_setting'),
          ),
          _GuideAction(
            icon: Icons.home_rounded,
            title: _t(zh: '首页问候与快捷指令', en: 'Home greeting and prompts'),
            subtitle: _t(
              zh: '决定打开聊天页时看到什么，以及常用指令是否固定。',
              en: 'Decide what appears on the chat home and which prompts stay pinned.',
            ),
            label: _t(zh: '编辑', en: 'Edit'),
            onTap: () => _openRoute('/home/home_setting'),
          ),
          _GuideAction(
            icon: Icons.alarm_rounded,
            title: _t(zh: '闹钟和提醒', en: 'Alarms and reminders'),
            subtitle: _t(
              zh: '选择默认铃声、本地音频或远程 mp3 链接。',
              en: 'Choose a default ringtone, local audio, or remote mp3 URL.',
            ),
            label: _t(zh: '设置', en: 'Set'),
            onTap: () => _openRoute('/home/alarm_setting'),
          ),
          _GuideAction(
            icon: Icons.drive_folder_upload_rounded,
            title: _t(zh: '用小万打开文件', en: 'Open files with Omnibot'),
            subtitle: _t(
              zh: '分别定义图片和其他文件进入对话或 workspace 的方式。',
              en: 'Define how images and files enter chat or the workspace.',
            ),
            label: _t(zh: '配置', en: 'Configure'),
            onTap: () => _openRoute('/home/open_with_omnibot_setting'),
          ),
        ],
      ),
      _GuideStep(
        accent: _roseAccent,
        icon: Icons.verified_user_rounded,
        eyebrow: _t(zh: '最后一步', en: 'Final step'),
        title: _t(
          zh: '收好隐私边界和维护入口。',
          en: 'Keep privacy and maintenance close.',
        ),
        description: _t(
          zh: '陪伴模式只访问你授权的 App；存储占用、日志和版本信息也都可以随时回来看。',
          en: 'Companion mode only sees apps you authorize; storage usage, logs, and version info stay easy to revisit.',
        ),
        primaryLabel: widget.replay
            ? _t(zh: '完成', en: 'Done')
            : _t(zh: '完成并进入小万', en: 'Finish and enter'),
        primaryIcon: Icons.check_rounded,
        onPrimary: () => unawaited(_finishGuide()),
        bullets: [
          _GuideBullet(
            label: _t(zh: '陪伴权限', en: 'Companion access'),
            value: _t(zh: '按 App 授权', en: 'Per app'),
            completed: true,
          ),
          _GuideBullet(
            label: _t(zh: '存储占用', en: 'Storage'),
            value: _t(zh: '可查看', en: 'Visible'),
            completed: true,
          ),
          _GuideBullet(
            label: _t(zh: '引导入口', en: 'Guide entry'),
            value: _t(zh: '设置内保留', en: 'In Settings'),
            completed: true,
          ),
        ],
        actions: [
          _GuideAction(
            icon: Icons.privacy_tip_rounded,
            title: _t(zh: '陪伴 App 权限', en: 'Companion app access'),
            subtitle: _t(
              zh: '只授权你愿意让小万陪伴和读取场景的应用。',
              en: 'Authorize only the apps where you want companion assistance.',
            ),
            label: _t(zh: '管理', en: 'Manage'),
            onTap: _openCompanionSettings,
          ),
          _GuideAction(
            icon: Icons.storage_rounded,
            title: _t(zh: '清点存储占用', en: 'Review storage usage'),
            subtitle: _t(
              zh: '查看 workspace、缓存、下载文件等空间使用情况。',
              en: 'Inspect workspace, cache, downloads, and other storage usage.',
            ),
            label: _t(zh: '查看', en: 'View'),
            onTap: () => _openRoute('/home/storage_usage'),
          ),
          _GuideAction(
            icon: Icons.info_rounded,
            title: _t(zh: '关于、日志与手册', en: 'About, logs, and guide'),
            subtitle: _t(
              zh: '版本信息、请求日志、运行日志和使用手册都在这里。',
              en: 'Find version info, request logs, runtime logs, and the user guide.',
            ),
            label: _t(zh: '打开', en: 'Open'),
            onTap: () => _openRoute('/my/about'),
          ),
        ],
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final pages = _pages;
    final currentStep = pages[_currentIndex];

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        primary: true,
        title: widget.replay ? _t(zh: '引导教程', en: 'Setup guide') : null,
        showLeading: widget.replay,
        backgroundColor: palette.pageBackground,
        centerTitle: true,
        trailing: TextButton(
          onPressed: _finishing ? null : () => unawaited(_finishGuide()),
          child: Text(
            _t(zh: '跳过全部', en: 'Skip all'),
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
              height: 1.4,
            ),
          ),
        ),
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            _GuideProgressHeader(
              currentIndex: _currentIndex,
              total: pages.length,
              accent: currentStep.accent,
              loading: _loadingSnapshot,
            ),
            Expanded(
              child: PageView.builder(
                controller: _pageController,
                physics: const BouncingScrollPhysics(),
                onPageChanged: (index) {
                  setState(() {
                    _currentIndex = index;
                  });
                },
                itemCount: pages.length,
                itemBuilder: (context, index) {
                  return FadeTransition(
                    opacity: _entranceOpacity,
                    child: SlideTransition(
                      position: _entranceOffset,
                      child: _GuideStepView(
                        key: ValueKey('guide_step_$index'),
                        step: pages[index],
                      ),
                    ),
                  );
                },
              ),
            ),
            _GuideBottomBar(
              step: currentStep,
              finishing: _finishing,
              onPrevious: _currentIndex == 0
                  ? null
                  : () => _goToPage(_currentIndex - 1),
              onSkipPage: _nextPage,
              skipLabel: _currentIndex == pages.length - 1
                  ? _t(zh: '完成', en: 'Done')
                  : _t(zh: '跳过本页', en: 'Skip page'),
            ),
          ],
        ),
      ),
    );
  }
}

class _GuideStepView extends StatelessWidget {
  const _GuideStepView({super.key, required this.step});

  final _GuideStep step;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final maxTextScale = MediaQuery.textScalerOf(
      context,
    ).clamp(minScaleFactor: 1, maxScaleFactor: 1.25);

    return MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: maxTextScale),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _GuideVisualAnchor(step: step),
            const SizedBox(height: 24),
            Text(
              step.eyebrow,
              style: TextStyle(
                color: step.accent,
                fontSize: 13,
                fontWeight: FontWeight.w700,
                height: 1.35,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              step.title,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 31,
                fontWeight: FontWeight.w800,
                height: 1.12,
                letterSpacing: 0,
              ),
            ),
            const SizedBox(height: 14),
            Text(
              step.description,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 15,
                fontWeight: FontWeight.w400,
                height: 1.62,
                letterSpacing: 0,
              ),
            ),
            if (step.bullets.isNotEmpty) ...[
              const SizedBox(height: 24),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: step.bullets
                    .map(
                      (bullet) =>
                          _GuideBulletChip(bullet: bullet, accent: step.accent),
                    )
                    .toList(growable: false),
              ),
            ],
            if (step.actions.isNotEmpty) ...[
              const SizedBox(height: 28),
              Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Configure here'
                    : '在这里配置',
                style: TextStyle(
                  color: palette.textTertiary,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 8),
              DecoratedBox(
                decoration: BoxDecoration(
                  color: palette.surfacePrimary.withValues(
                    alpha: isDark ? 0.72 : 0.88,
                  ),
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(
                    color: palette.borderSubtle.withValues(
                      alpha: isDark ? 0.7 : 1,
                    ),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: palette.shadowColor.withValues(
                        alpha: isDark ? 0.24 : 0.8,
                      ),
                      blurRadius: 24,
                      offset: const Offset(0, 12),
                    ),
                  ],
                ),
                child: Column(
                  children: List.generate(step.actions.length, (index) {
                    final action = step.actions[index];
                    final isLast = index == step.actions.length - 1;
                    return Column(
                      children: [
                        _GuideActionRow(action: action, accent: step.accent),
                        if (!isLast)
                          Divider(
                            height: 1,
                            indent: 64,
                            color: palette.borderSubtle.withValues(
                              alpha: isDark ? 0.52 : 0.82,
                            ),
                          ),
                      ],
                    );
                  }),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _GuideVisualAnchor extends StatelessWidget {
  const _GuideVisualAnchor({required this.step});

  final _GuideStep step;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final accent = step.accent;
    final foreground = isDark ? Colors.white : palette.textPrimary;

    return SizedBox(
      height: 172,
      width: double.infinity,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: isDark
                ? [
                    Color.lerp(palette.surfacePrimary, accent, 0.12)!,
                    Color.lerp(palette.surfaceSecondary, accent, 0.2)!,
                  ]
                : [
                    Color.lerp(Colors.white, accent, 0.05)!,
                    Color.lerp(palette.surfaceSecondary, accent, 0.18)!,
                  ],
          ),
          border: Border.all(
            color: Color.lerp(
              palette.borderSubtle,
              accent,
              isDark ? 0.16 : 0.24,
            )!,
          ),
        ),
        child: Stack(
          children: [
            Positioned(
              right: -16,
              bottom: -24,
              child: Icon(
                step.icon,
                size: 150,
                color: accent.withValues(alpha: isDark ? 0.16 : 0.12),
              ),
            ),
            Positioned(
              left: 24,
              top: 24,
              child: Container(
                width: 66,
                height: 66,
                decoration: BoxDecoration(
                  color: accent.withValues(alpha: isDark ? 0.22 : 0.16),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: accent.withValues(alpha: 0.32)),
                ),
                child: Icon(step.icon, color: foreground, size: 32),
              ),
            ),
            Positioned(
              left: 24,
              right: 24,
              bottom: 22,
              child: Row(
                children: [
                  Expanded(
                    child: Container(
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderSubtle.withValues(alpha: 0.8),
                        borderRadius: BorderRadius.circular(99),
                      ),
                      child: FractionallySizedBox(
                        alignment: Alignment.centerLeft,
                        widthFactor: 0.68,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: accent,
                            borderRadius: BorderRadius.circular(99),
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Icon(
                    Icons.swipe_rounded,
                    size: 20,
                    color: palette.textTertiary,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GuideProgressHeader extends StatelessWidget {
  const _GuideProgressHeader({
    required this.currentIndex,
    required this.total,
    required this.accent,
    required this.loading,
  });

  final int currentIndex;
  final int total;
  final Color accent;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final progress = (currentIndex + 1) / total;

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
      child: Row(
        children: [
          Text(
            '${currentIndex + 1}/$total',
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 13,
              fontWeight: FontWeight.w700,
              height: 1.4,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(99),
              child: LinearProgressIndicator(
                minHeight: 6,
                value: loading ? null : progress,
                backgroundColor: palette.borderSubtle,
                valueColor: AlwaysStoppedAnimation<Color>(accent),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _GuideBottomBar extends StatelessWidget {
  const _GuideBottomBar({
    required this.step,
    required this.finishing,
    required this.onPrevious,
    required this.onSkipPage,
    required this.skipLabel,
  });

  final _GuideStep step;
  final bool finishing;
  final VoidCallback? onPrevious;
  final VoidCallback onSkipPage;
  final String skipLabel;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: palette.pageBackground,
        border: Border(
          top: BorderSide(
            color: palette.borderSubtle.withValues(alpha: isDark ? 0.5 : 0.8),
          ),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
        child: Row(
          children: [
            Semantics(
              button: true,
              label: Localizations.localeOf(context).languageCode == 'en'
                  ? 'Previous page'
                  : '上一页',
              child: IconButton(
                onPressed: onPrevious,
                icon: const Icon(Icons.arrow_back_rounded),
                color: palette.textSecondary,
                disabledColor: palette.textTertiary.withValues(alpha: 0.42),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Semantics(
                button: true,
                label: skipLabel,
                child: TextButton(
                  onPressed: finishing ? null : onSkipPage,
                  style: TextButton.styleFrom(
                    minimumSize: const Size(0, 48),
                    foregroundColor: palette.textSecondary,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  child: Text(
                    skipLabel,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      height: 1.3,
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 2,
              child: Semantics(
                button: true,
                label: step.primaryLabel,
                child: FilledButton.icon(
                  onPressed: finishing ? null : step.onPrimary,
                  icon: finishing
                      ? SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: isDark ? palette.textPrimary : Colors.white,
                          ),
                        )
                      : Icon(step.primaryIcon, size: 19),
                  label: Text(
                    step.primaryLabel,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                      height: 1.3,
                      letterSpacing: 0,
                    ),
                  ),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(0, 50),
                    backgroundColor: step.accent,
                    foregroundColor: _foregroundFor(step.accent),
                    disabledBackgroundColor: palette.borderStrong,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GuideBulletChip extends StatelessWidget {
  const _GuideBulletChip({required this.bullet, required this.accent});

  final _GuideBullet bullet;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final statusColor = bullet.completed ? accent : palette.textTertiary;

    return Container(
      constraints: const BoxConstraints(minHeight: 42),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: bullet.completed
            ? accent.withValues(alpha: isDark ? 0.18 : 0.12)
            : palette.surfacePrimary,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: bullet.completed
              ? accent.withValues(alpha: 0.38)
              : palette.borderSubtle,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            bullet.completed
                ? Icons.check_circle_rounded
                : Icons.circle_outlined,
            size: 16,
            color: statusColor,
          ),
          const SizedBox(width: 7),
          Text(
            bullet.label,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w700,
              height: 1.3,
            ),
          ),
          const SizedBox(width: 6),
          Text(
            bullet.value,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 12,
              fontWeight: FontWeight.w800,
              height: 1.3,
            ),
          ),
        ],
      ),
    );
  }
}

class _GuideActionRow extends StatelessWidget {
  const _GuideActionRow({required this.action, required this.accent});

  final _GuideAction action;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final statusColor = action.completed ? accent : palette.textTertiary;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: action.onTap,
        borderRadius: BorderRadius.circular(18),
        child: Semantics(
          button: true,
          label: '${action.title} ${action.label}',
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 14, 12, 14),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: accent.withValues(alpha: 0.13),
                    borderRadius: BorderRadius.circular(13),
                  ),
                  child: Icon(action.icon, size: 21, color: accent),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              action.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 14,
                                fontWeight: FontWeight.w800,
                                height: 1.35,
                                letterSpacing: 0,
                              ),
                            ),
                          ),
                          if (action.completed)
                            Icon(
                              Icons.check_circle_rounded,
                              size: 16,
                              color: statusColor,
                            ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        action.subtitle,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w400,
                          height: 1.45,
                          letterSpacing: 0,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                Container(
                  constraints: const BoxConstraints(minWidth: 48),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 7,
                  ),
                  decoration: BoxDecoration(
                    color: accent.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Text(
                    action.label,
                    textAlign: TextAlign.center,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: accent,
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      height: 1.2,
                    ),
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

Color _foregroundFor(Color background) {
  return ThemeData.estimateBrightnessForColor(background) == Brightness.dark
      ? Colors.white
      : const Color(0xFF171717);
}

class _GuideStep {
  const _GuideStep({
    required this.accent,
    required this.icon,
    required this.eyebrow,
    required this.title,
    required this.description,
    required this.primaryLabel,
    required this.primaryIcon,
    required this.onPrimary,
    this.bullets = const [],
    this.actions = const [],
  });

  final Color accent;
  final IconData icon;
  final String eyebrow;
  final String title;
  final String description;
  final String primaryLabel;
  final IconData primaryIcon;
  final VoidCallback onPrimary;
  final List<_GuideBullet> bullets;
  final List<_GuideAction> actions;
}

class _GuideBullet {
  const _GuideBullet({
    required this.label,
    required this.value,
    required this.completed,
  });

  final String label;
  final String value;
  final bool completed;
}

class _GuideAction {
  const _GuideAction({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.label,
    required this.onTap,
    this.completed = false,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String label;
  final Future<void> Function() onTap;
  final bool completed;
}

class _GuideSnapshot {
  const _GuideSnapshot({
    required this.corePermissionReadyCount,
    required this.corePermissionTotalCount,
    required this.cloudModelConfigured,
    required this.localModelReady,
    required this.workspaceMemoryConfigured,
    required this.mcpEnabled,
    required this.vibrationEnabled,
    required this.autoBackToChatEnabled,
    required this.independentSendButtonEnabled,
    required this.publicStorageGranted,
    required this.shizukuGranted,
  });

  final int corePermissionReadyCount;
  final int corePermissionTotalCount;
  final bool cloudModelConfigured;
  final bool localModelReady;
  final bool workspaceMemoryConfigured;
  final bool mcpEnabled;
  final bool vibrationEnabled;
  final bool autoBackToChatEnabled;
  final bool independentSendButtonEnabled;
  final bool publicStorageGranted;
  final bool shizukuGranted;

  bool get corePermissionsReady =>
      corePermissionTotalCount > 0 &&
      corePermissionReadyCount >= corePermissionTotalCount;

  bool get advancedAccessReady => publicStorageGranted || shizukuGranted;

  String get permissionProgressLabel =>
      '$corePermissionReadyCount/$corePermissionTotalCount';

  static _GuideSnapshot empty() {
    return const _GuideSnapshot(
      corePermissionReadyCount: 0,
      corePermissionTotalCount: 4,
      cloudModelConfigured: false,
      localModelReady: false,
      workspaceMemoryConfigured: false,
      mcpEnabled: false,
      vibrationEnabled: true,
      autoBackToChatEnabled: true,
      independentSendButtonEnabled: true,
      publicStorageGranted: false,
      shizukuGranted: false,
    );
  }

  static Future<_GuideSnapshot> load() async {
    var coreReady = 0;
    var coreTotal = 4;
    var cloudConfigured = false;
    var localReady = false;
    var workspaceConfigured = false;
    var mcpEnabled = false;
    var vibrationEnabled = true;
    var autoBackEnabled = true;
    var independentSendEnabled = true;
    var publicStorageGranted = false;
    var shizukuGranted = false;

    try {
      final deviceInfo = await DeviceService.getDeviceInfo();
      final brand = (deviceInfo?['brand'] as String?)?.toLowerCase() ?? 'other';
      final specs = PermissionRegistry.getPermissionsByLevel(
        brand: brand,
        level: PermissionLevel.fullExecution,
      );
      final missing = await PermissionService.getMissing(specs);
      coreTotal = specs.isEmpty ? 4 : specs.length;
      coreReady = (coreTotal - missing.length).clamp(0, coreTotal);
    } catch (e) {
      debugPrint('Guide permission snapshot failed: $e');
    }

    try {
      final profiles = await ModelProviderConfigService.listProfiles();
      cloudConfigured = profiles.profiles.any(
        (profile) =>
            profile.configured ||
            (!profile.readOnly && profile.apiKey.trim().isNotEmpty),
      );
    } catch (e) {
      debugPrint('Guide model provider snapshot failed: $e');
    }

    if (localModelFeature.enabled) {
      try {
        localReady =
            await localModelFeature.findInstalledRecommendedModelId() != null;
      } catch (e) {
        debugPrint('Guide local model snapshot failed: $e');
      }
    }

    try {
      final embeddingConfig = await WorkspaceMemoryService.getEmbeddingConfig();
      workspaceConfigured = embeddingConfig.configured;
    } catch (e) {
      debugPrint('Guide workspace memory snapshot failed: $e');
    }

    try {
      final info = await McpServerService.getState();
      mcpEnabled = info?.enabled == true;
    } catch (e) {
      debugPrint('Guide MCP snapshot failed: $e');
    }

    try {
      vibrationEnabled = await CacheUtil.getBool(
        'app_vibrate',
        defaultValue: true,
      );
    } catch (e) {
      debugPrint('Guide vibration snapshot failed: $e');
    }

    try {
      autoBackEnabled = await StorageService.isAutoBackToChatAfterTaskEnabled();
      independentSendEnabled =
          StorageService.isIndependentChatSendButtonEnabled();
    } catch (e) {
      debugPrint('Guide experience snapshot failed: $e');
    }

    try {
      publicStorageGranted = await isPublicStorageAccessGranted();
    } catch (e) {
      debugPrint('Guide public storage snapshot failed: $e');
    }

    try {
      shizukuGranted = (await getShizukuStatus()).isGranted;
    } catch (e) {
      debugPrint('Guide shizuku snapshot failed: $e');
    }

    return _GuideSnapshot(
      corePermissionReadyCount: coreReady,
      corePermissionTotalCount: coreTotal,
      cloudModelConfigured: cloudConfigured,
      localModelReady: localReady,
      workspaceMemoryConfigured: workspaceConfigured,
      mcpEnabled: mcpEnabled,
      vibrationEnabled: vibrationEnabled,
      autoBackToChatEnabled: autoBackEnabled,
      independentSendButtonEnabled: independentSendEnabled,
      publicStorageGranted: publicStorageGranted,
      shizukuGranted: shizukuGranted,
    );
  }
}
