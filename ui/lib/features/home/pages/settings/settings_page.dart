import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/svg.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/hide_from_recents_service.dart';
import 'package:ui/services/mcp_server_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/workspace_memory_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/accessibility_utils.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool vibrationEnabled = true;
  bool hideFromRecentsEnabled = false;
  bool _autoBackToChatAfterTaskEnabled = true;
  bool _mcpEnabled = false;
  bool _mcpLoaded = false;
  bool _mcpBusy = false;
  McpServerInfo? _mcpInfo;
  bool _workspaceMemoryLoaded = false;
  WorkspaceMemoryEmbeddingConfig? _embeddingConfig;
  StreamSubscription<AgentAiConfigChangedEvent>? _configChangedSubscription;

  String _localizedText({required String zh, required String en}) {
    return AppAccessibility.localizedText(context, zh: zh, en: en);
  }

  Future<void> _announceSettingState(String title, bool enabled) async {
    await AppAccessibility.announce(
      context,
      AppAccessibility.isEnglish(context)
          ? '$title ${enabled ? 'on' : 'off'}'
          : '$title已${enabled ? '开启' : '关闭'}',
    );
  }

  @override
  void initState() {
    super.initState();
    _autoBackToChatAfterTaskEnabled =
        StorageService.getBool(
          StorageService.kAutoBackToChatAfterTaskKey,
          defaultValue: true,
        ) ??
        true;
    _loadVibrationState();
    _loadHideFromRecentsState();
    _loadAutoBackToChatAfterTaskState();
    _loadMcpServerState();
    _loadWorkspaceMemoryState();
    _configChangedSubscription = AssistsMessageService
        .agentAiConfigChangedStream
        .listen((event) {
          if (event.source != 'file' || !mounted) {
            return;
          }
          _loadWorkspaceMemoryState();
        });
  }

  @override
  void dispose() {
    _configChangedSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadVibrationState() async {
    try {
      final enabled = await CacheUtil.getBool(
        'app_vibrate',
        defaultValue: true,
      );
      setState(() {
        vibrationEnabled = enabled;
      });
      debugPrint('Vibration state loaded: $vibrationEnabled');
    } catch (e) {
      debugPrint('Error loading vibration state: $e');
    }
  }

  Future<void> _loadHideFromRecentsState() async {
    try {
      final enabled =
          StorageService.getBool('hide_from_recents', defaultValue: false) ??
          false;
      setState(() {
        hideFromRecentsEnabled = enabled;
      });
    } catch (e) {
      debugPrint('Error loading hide from recents state: $e');
    }
  }

  Future<void> _onHideFromRecentsChanged(bool value) async {
    setState(() {
      hideFromRecentsEnabled = value;
    });

    final success = await HideFromRecentsService.setExcludeFromRecents(value);
    if (!success) {
      if (!mounted) return;
      setState(() {
        hideFromRecentsEnabled = !value;
      });
      showToast(context.l10n.settingsHideRecentsFailed, type: ToastType.error);
      await AppAccessibility.announce(
        context,
        context.l10n.settingsHideRecentsFailed,
      );
      return;
    }
    await _announceSettingState(context.l10n.settingsHideRecentsTitle, value);
  }

  Future<void> _loadAutoBackToChatAfterTaskState() async {
    try {
      final enabled = await StorageService.isAutoBackToChatAfterTaskEnabled();
      if (!mounted) return;
      if (_autoBackToChatAfterTaskEnabled == enabled) return;
      setState(() {
        _autoBackToChatAfterTaskEnabled = enabled;
      });
    } catch (e) {
      debugPrint('Error loading auto back to chat setting: $e');
    }
  }

  Future<void> _onAutoBackToChatAfterTaskChanged(bool value) async {
    try {
      await StorageService.setAutoBackToChatAfterTaskEnabled(value);
      final synced =
          await AssistsMessageService.setAutoBackToChatAfterTaskEnabled(value);
      if (!synced) {
        throw Exception('native_sync_failed');
      }
      if (!mounted) return;
      setState(() {
        _autoBackToChatAfterTaskEnabled = value;
      });
      showToast(
        value
            ? context.l10n.settingsAutoBackEnabledToast
            : context.l10n.settingsAutoBackDisabledToast,
      );
      await _announceSettingState(context.l10n.settingsAutoBackTitle, value);
    } catch (e) {
      if (!mounted) return;
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
      await AppAccessibility.announce(context, context.l10n.settingsSaveFailed);
    }
  }

  Future<void> _loadMcpServerState() async {
    try {
      final info = await McpServerService.getState();
      if (!mounted) return;
      setState(() {
        _mcpInfo = info;
        _mcpEnabled = info?.enabled == true;
        _mcpLoaded = true;
      });
    } catch (e) {
      debugPrint('Load MCP state failed: $e');
      if (!mounted) return;
      setState(() {
        _mcpLoaded = true;
      });
    }
  }

  Future<void> _loadWorkspaceMemoryState() async {
    try {
      final results = await Future.wait([
        WorkspaceMemoryService.getEmbeddingConfig(),
        WorkspaceMemoryService.getRollupStatus(),
      ]);
      if (!mounted) return;
      setState(() {
        _embeddingConfig = results[0] as WorkspaceMemoryEmbeddingConfig;
        _workspaceMemoryLoaded = true;
      });
    } catch (e) {
      debugPrint('Load workspace memory state failed: $e');
      if (!mounted) return;
      setState(() {
        _workspaceMemoryLoaded = true;
      });
    }
  }

  Future<void> _toggleMcpServer(bool enable) async {
    if (_mcpBusy) return;
    setState(() {
      _mcpBusy = true;
      _mcpEnabled = enable;
    });
    try {
      final info = await McpServerService.setEnabled(enable);
      if (!mounted) return;
      setState(() {
        _mcpInfo = info;
        _mcpEnabled = info?.enabled == true;
      });
      if (enable) {
        final endpoint = info?.endpoint ?? '';
        if (endpoint.isNotEmpty) {
          showToast(
            context.l10n.settingsMcpEnabledToast(endpoint),
            type: ToastType.success,
          );
        }
      } else {
        showToast(context.l10n.settingsMcpDisabledToast);
      }
      await _announceSettingState(context.l10n.settingsLocalServiceTitle, enable);
    } on PlatformException catch (e) {
      if (!mounted) return;
      showToast(
        e.message ?? context.l10n.settingsMcpToggleFailed,
        type: ToastType.error,
      );
      setState(() {
        _mcpEnabled = !enable;
      });
      await AppAccessibility.announce(
        context,
        e.message ?? context.l10n.settingsMcpToggleFailed,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(context.l10n.settingsMcpToggleFailed, type: ToastType.error);
      setState(() {
        _mcpEnabled = !enable;
      });
      await AppAccessibility.announce(
        context,
        context.l10n.settingsMcpToggleFailed,
      );
    } finally {
      if (mounted) {
        setState(() {
          _mcpBusy = false;
        });
      }
    }
  }

  void _showMcpInfo() {
    final info = _mcpInfo;
    if (info == null || info.endpoint.isEmpty) return;

    showModalBottomSheet<void>(
      context: context,
      builder: (context) {
        return Semantics(
          scopesRoute: true,
          namesRoute: true,
          label: context.l10n.settingsMcpLocalService,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  context.l10n.settingsMcpLocalService,
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 12),
                Text(context.l10n.settingsMcpAddress),
                SelectableText(info.endpoint),
                const SizedBox(height: 8),
                Text(context.l10n.settingsMcpToken),
                SelectableText(
                  info.token.isEmpty
                      ? context.l10n.settingsNotGenerated
                      : info.token,
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    TextButton(
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: info.endpoint));
                        Navigator.of(context).pop();
                        showToast(context.l10n.settingsCopiedAddress);
                      },
                      child: Text(context.l10n.settingsCopyAddress),
                    ),
                    TextButton(
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: info.token));
                        Navigator.of(context).pop();
                        showToast(context.l10n.settingsCopiedToken);
                      },
                      child: Text(context.l10n.settingsCopyToken),
                    ),
                    TextButton(
                      onPressed: () async {
                        Navigator.of(context).pop();
                        try {
                          final refreshed = await McpServerService.refreshToken();
                          if (!mounted) return;
                          setState(() {
                            _mcpInfo = refreshed ?? _mcpInfo;
                          });
                          showToast(context.l10n.settingsTokenRefreshed);
                        } catch (_) {
                          showToast(
                            context.l10n.settingsTokenRefreshFailed,
                            type: ToastType.error,
                          );
                        }
                      },
                      child: Text(context.l10n.settingsRefreshToken),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  context.l10n.settingsMcpSecurityNotice,
                  style: TextStyle(fontSize: 12, color: Colors.black54),
                ),
                const SizedBox(height: 8),
              ],
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final workspaceMemoryConfigured = _embeddingConfig?.configured == true;
    final workspaceMemorySubtitle = !_workspaceMemoryLoaded
        ? context.l10n.settingsWorkspaceMemoryLoading
        : workspaceMemoryConfigured
        ? context.l10n.settingsWorkspaceMemoryEnabled
        : context.l10n.settingsWorkspaceMemoryLexical;
    final sections = _buildSections(workspaceMemorySubtitle);

    return Semantics(
      scopesRoute: true,
      namesRoute: true,
      label: context.l10n.settingsTitle,
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(title: context.l10n.settingsTitle, primary: true),
        body: SafeArea(
          child: ListView.separated(
            padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
            itemCount: sections.length,
            separatorBuilder: (_, __) => const SizedBox(height: 24),
            itemBuilder: (context, index) {
              return _buildSettingsSection(sections[index]);
            },
          ),
        ),
      ),
    );
  }

  List<_SettingSection> _buildSections(String workspaceMemorySubtitle) {
    return [
      _SettingSection(
        label: context.l10n.settingsSectionModelMemory,
        items: [
          _SettingItem(
            icon: Icons.smart_toy_outlined,
            iconSvg: 'assets/home/vlm_model_setting_icon.svg',
            title: context.l10n.settingsModelProviderTitle,
            subtitle: context.l10n.settingsModelProviderSubtitle,
            onTap: () {
              GoRouterManager.push('/home/vlm_model_setting');
            },
          ),
          _SettingItem(
            icon: Icons.tune_outlined,
            iconSvg: 'assets/home/scene_model_setting_icon.svg',
            title: context.l10n.settingsSceneModelTitle,
            subtitle: context.l10n.settingsSceneModelSubtitle,
            onTap: () {
              GoRouterManager.push('/home/scene_model_setting');
            },
          ),
          _SettingItem(
            icon: Icons.memory_outlined,
            iconSvg: 'assets/home/local_model_cpu_icon.svg',
            title: context.l10n.settingsLocalModelsTitle,
            subtitle: context.l10n.settingsLocalModelsSubtitle,
            onTap: () {
              GoRouterManager.push('/home/local_models?tab=service');
            },
          ),
          _SettingItem(
            icon: Icons.cloud_sync_outlined,
            iconSvg: 'assets/home/mem0_cloud_setting_icon.svg',
            title: context.l10n.settingsWorkspaceMemoryTitle,
            subtitle: workspaceMemorySubtitle,
            onTap: () async {
              await GoRouterManager.pushForResult(
                '/home/workspace_memory_setting',
              );
              _loadWorkspaceMemoryState();
            },
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionServiceEnvironment,
        items: [
          _SettingItem(
            icon: Icons.extension_outlined,
            iconSvg: 'assets/home/mcp_tools_setting_icon.svg',
            title: context.l10n.settingsMcpToolsTitle,
            subtitle: context.l10n.settingsMcpToolsSubtitle,
            onTap: () {
              GoRouterManager.push('/home/mcp_tools');
            },
          ),
          _SettingItem(
            icon: Icons.cloud_outlined,
            iconSvg: 'assets/home/local_mcp_service_setting_icon.svg',
            title: context.l10n.settingsLocalServiceTitle,
            subtitle: context.l10n.settingsLocalServiceSubtitle,
            toggled: _mcpEnabled,
            semanticValue: _mcpLoaded
                ? AppAccessibility.toggleStateLabel(context, _mcpEnabled)
                : _localizedText(zh: '加载中', en: 'Loading'),
            semanticHint: _localizedText(
              zh: '双击切换本地服务',
              en: 'Double tap to toggle the local service',
            ),
            trailing: _buildSwitchTrailing(
              value: _mcpEnabled,
              enabled: _mcpLoaded && !_mcpBusy,
              loading: !_mcpLoaded,
              onToggle: (val) async {
                await _toggleMcpServer(val);
              },
            ),
            onTap: _mcpEnabled && !_mcpBusy ? _showMcpInfo : null,
          ),
          _SettingItem(
            icon: Icons.code,
            iconSvg: 'assets/home/termux.svg',
            iconColor: AppColors.buttonPrimary,
            title: context.l10n.settingsAlpineTitle,
            subtitle: context.l10n.settingsAlpineSubtitle,
            onTap: () {
              GoRouterManager.push('/home/termux_setting');
            },
          ),
          _SettingItem(
            icon: Icons.visibility_off_outlined,
            iconSvg: 'assets/home/hide_recents_setting_icon.svg',
            title: context.l10n.settingsHideRecentsTitle,
            subtitle: context.l10n.settingsHideRecentsSubtitle,
            toggled: hideFromRecentsEnabled,
            semanticValue: AppAccessibility.toggleStateLabel(
              context,
              hideFromRecentsEnabled,
            ),
            semanticHint: _localizedText(
              zh: '双击切换最近任务隐藏开关',
              en: 'Double tap to toggle hide from recents',
            ),
            trailing: _buildSwitchTrailing(
              value: hideFromRecentsEnabled,
              onToggle: _onHideFromRecentsChanged,
            ),
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionExperienceAppearance,
        items: [
          _SettingItem(
            icon: Icons.alarm_outlined,
            title: context.l10n.settingsAlarmTitle,
            subtitle: context.l10n.settingsAlarmSubtitle,
            onTap: () {
              GoRouterManager.push('/home/alarm_setting');
            },
          ),
          _SettingItem(
            icon: Icons.wallpaper_outlined,
            title: context.l10n.settingsAppearanceTitle,
            subtitle: context.l10n.settingsAppearanceSubtitle,
            onTap: () {
              GoRouterManager.push('/home/background_setting');
            },
          ),
          _SettingItem(
            icon: Icons.vibration,
            iconSvg: 'assets/home/vibration_icon.svg',
            title: context.l10n.settingsVibrationTitle,
            subtitle: context.l10n.settingsVibrationSubtitle,
            toggled: vibrationEnabled,
            semanticValue: AppAccessibility.toggleStateLabel(
              context,
              vibrationEnabled,
            ),
            semanticHint: _localizedText(
              zh: '双击切换振动反馈',
              en: 'Double tap to toggle vibration feedback',
            ),
            trailing: _buildSwitchTrailing(
              value: vibrationEnabled,
              onToggle: (val) async {
                await CacheUtil.cacheBool('app_vibrate', val);
                setState(() {
                  vibrationEnabled = val;
                });
                await _announceSettingState(
                  context.l10n.settingsVibrationTitle,
                  val,
                );
              },
            ),
          ),
          _SettingItem(
            icon: Icons.chat_outlined,
            iconSvg: 'assets/home/auto_back_chat_setting_icon.svg',
            title: context.l10n.settingsAutoBackTitle,
            subtitle: context.l10n.settingsAutoBackSubtitle,
            toggled: _autoBackToChatAfterTaskEnabled,
            semanticValue: AppAccessibility.toggleStateLabel(
              context,
              _autoBackToChatAfterTaskEnabled,
            ),
            semanticHint: _localizedText(
              zh: '双击切换任务结束后返回聊天页',
              en: 'Double tap to toggle returning to chat after tasks',
            ),
            trailing: _buildSwitchTrailing(
              value: _autoBackToChatAfterTaskEnabled,
              onToggle: _onAutoBackToChatAfterTaskChanged,
            ),
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionPermissionInfo,
        items: [
          _SettingItem(
            icon: Icons.admin_panel_settings_outlined,
            iconSvg: 'assets/home/app_permission_authorize_icon.svg',
            title: context.l10n.authorizePageTitle,
            subtitle: context.trLegacy('查看并配置无障碍、悬浮窗、Shizuku 等权限'),
            onTap: () {
              GoRouterManager.push('/home/authorize_setting');
            },
          ),
          _SettingItem(
            icon: Icons.security,
            iconSvg: 'assets/home/companion_permission_setting_icon.svg',
            title: context.l10n.settingsCompanionPermissionTitle,
            subtitle: context.l10n.settingsCompanionPermissionSubtitle,
            onTap: () async {
              try {
                final granted = await ensureInstalledAppsPermission();
                if (granted == true) {
                  GoRouterManager.push('/home/companion_setting');
                }
              } catch (e) {
                debugPrint('Failed to request installed apps permission: $e');
                showToast(context.l10n.settingsInstalledAppsPermissionFailed);
              }
            },
          ),
          _SettingItem(
            icon: Icons.storage_outlined,
            title: context.l10n.storageUsageTitle,
            subtitle: context.l10n.storageUsageSubtitle,
            onTap: () {
              GoRouterManager.push('/home/storage_usage');
            },
          ),
          _SettingItem(
            icon: Icons.info_outline,
            iconSvg: 'assets/home/about_icon.svg',
            title: context.l10n.settingsAboutTitle,
            onTap: () {
              GoRouterManager.push('/my/about');
            },
          ),
        ],
      ),
    ];
  }

  Widget _buildSettingsSection(_SettingSection section) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
          child: Row(
            children: [
              Text(
                context.trLegacy(section.label),
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.6,
                  color: palette.textTertiary,
                  fontFamily: 'PingFang SC',
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Container(
                  height: 1,
                  color: palette.borderSubtle.withValues(
                    alpha: context.isDarkTheme ? 0.56 : 0.8,
                  ),
                ),
              ),
            ],
          ),
        ),
        Column(
          children: List.generate(section.items.length, (index) {
            final isLast = index == section.items.length - 1;
            return Column(
              children: [
                _buildSettingTile(section.items[index], isLast: isLast),
                if (!isLast)
                  Padding(
                    padding: const EdgeInsets.only(left: 30),
                    child: Divider(
                      height: 1,
                      thickness: 1,
                      color: palette.borderSubtle.withValues(
                        alpha: context.isDarkTheme ? 0.5 : 0.78,
                      ),
                    ),
                  ),
              ],
            );
          }),
        ),
      ],
    );
  }

  Widget _buildSettingTile(_SettingItem item, {required bool isLast}) {
    final palette = context.omniPalette;
    return Semantics(
      container: true,
      button: item.toggled == null && item.onTap != null,
      enabled: item.onTap != null,
      toggled: item.toggled,
      label: context.trLegacy(item.title),
      value: item.semanticValue ??
          (item.subtitle == null ? null : context.trLegacy(item.subtitle!)),
      hint: item.semanticHint,
      child: ExcludeSemantics(
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: item.onTap,
            borderRadius: BorderRadius.circular(14),
            splashColor: palette.accentPrimary.withValues(alpha: 0.08),
            highlightColor: Colors.transparent,
            child: Padding(
              padding: EdgeInsets.fromLTRB(4, 14, 2, isLast ? 14 : 13),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  _buildLeadingIcon(item),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          context.trLegacy(item.title),
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: palette.textPrimary,
                            height: 1.5,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        if (item.subtitle != null) ...[
                          const SizedBox(height: 2),
                          Text(
                            context.trLegacy(item.subtitle!),
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 11,
                              fontFamily: 'PingFang SC',
                              fontWeight: FontWeight.w400,
                              height: 1.55,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (item.trailing != null)
                    item.trailing!
                  else if (item.onTap != null)
                    Padding(
                      padding: const EdgeInsets.only(left: 12),
                      child: Icon(
                        Icons.chevron_right_rounded,
                        size: 18,
                        color: palette.textTertiary,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildLeadingIcon(_SettingItem item) {
    final palette = context.omniPalette;
    final iconColor = item.iconColor ?? palette.textPrimary;
    return SizedBox(
      width: 18,
      height: 18,
      child: item.iconSvg != null
          ? SvgPicture.asset(
              item.iconSvg!,
              width: 18,
              height: 18,
              colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
            )
          : item.icon != null
          ? Icon(item.icon, size: 18, color: iconColor)
          : const SizedBox.shrink(),
    );
  }

  Widget _buildSwitchTrailing({
    required bool value,
    required ValueChanged<bool> onToggle,
    bool enabled = true,
    bool loading = false,
  }) {
    final palette = context.omniPalette;
    return ExcludeSemantics(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: enabled && !loading ? () => onToggle(!value) : null,
        child: Padding(
          padding: const EdgeInsets.only(left: 12),
          child: loading
              ? Container(
                  width: 32,
                  height: 18.67,
                  decoration: BoxDecoration(
                    color: palette.borderStrong,
                    borderRadius: BorderRadius.circular(28.75),
                  ),
                )
              : AbsorbPointer(
                  child: Opacity(
                    opacity: enabled ? 1 : 0.5,
                    child: FlutterSwitch(
                      width: 32,
                      height: 18.67,
                      toggleSize: 11.3,
                      padding: 3,
                      activeColor: palette.accentPrimary,
                      inactiveColor: palette.borderStrong,
                      borderRadius: 28.75,
                      value: value,
                      onToggle: onToggle,
                    ),
                  ),
                ),
        ),
      ),
    );
  }
}

class _SettingSection {
  final String label;
  final List<_SettingItem> items;

  const _SettingSection({required this.label, required this.items});
}

class _SettingItem {
  final IconData? icon;
  final String? iconSvg;
  final Color? iconColor;
  final String title;
  final String? subtitle;
  final bool? toggled;
  final String? semanticValue;
  final String? semanticHint;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _SettingItem({
    this.icon,
    this.iconSvg,
    this.iconColor,
    required this.title,
    this.subtitle,
    this.toggled,
    this.semanticValue,
    this.semanticHint,
    this.trailing,
    this.onTap,
  });
}
