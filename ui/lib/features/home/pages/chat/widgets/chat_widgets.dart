import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/theme_context.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/app_background_service.dart';
import '../../../../../widgets/app_background_widgets.dart';
import '../chat_page_models.dart';
import '../utils/agent_run_timeline.dart';
import '../../command_overlay/widgets/message_bubble.dart';
import '../../command_overlay/widgets/chat_input_area.dart';
import 'agent_run_group_message.dart';

const String _chatAppBarUpdateSparklesSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 '
    '1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 '
    '1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-'
    '1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-'
    '1.594z"/>'
    '<path d="M20 2v4"/>'
    '<path d="M22 4h-4"/>'
    '<circle cx="4" cy="20" r="2"/>'
    '</svg>';

const String _chatAppBarAgentIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M12 8V4H8"/>'
    '<rect width="16" height="12" x="4" y="8" rx="2"/>'
    '<path d="M2 14h2"/>'
    '<path d="M20 14h2"/>'
    '<path d="M15 13v2"/>'
    '<path d="M9 13v2"/>'
    '</svg>';

const String _chatAppBarCodexIconSvg =
    '<svg height="1em" style="flex:none;line-height:1" viewBox="0 0 24 24" '
    'width="1em" xmlns="http://www.w3.org/2000/svg">'
    '<title>Codex</title>'
    '<path d="M9.064 3.344a4.578 4.578 0 012.285-.312c1 .115 1.891.54 '
    '2.673 1.275.01.01.024.017.037.021a.09.09 0 00.043 0 4.55 4.55 0 '
    '013.046.275l.047.022.116.057a4.581 4.581 0 012.188 2.399c.209.51.313 '
    '1.041.315 1.595a4.24 4.24 0 01-.134 1.223.123.123 0 00.03.115c.594.607 '
    '.988 1.33 1.183 2.17.289 1.425-.007 2.71-.887 3.854l-.136.166a4.548 '
    '4.548 0 01-2.201 1.388.123.123 0 00-.081.076c-.191.551-.383 1.023-.74 '
    '1.494-.9 1.187-2.222 1.846-3.711 1.838-1.187-.006-2.239-.44-3.157-'
    '1.302a.107.107 0 00-.105-.024c-.388.125-.78.143-1.204.138a4.441 4.441 '
    '0 01-1.945-.466 4.544 4.544 0 01-1.61-1.335c-.152-.202-.303-.392-.414-'
    '.617a5.81 5.81 0 01-.37-.961 4.582 4.582 0 01-.014-2.298.124.124 0 '
    '00.006-.056.085.085 0 00-.027-.048 4.467 4.467 0 01-1.034-1.651 '
    '3.896 3.896 0 01-.251-1.192 5.189 5.189 0 01.141-1.6c.337-1.112.982-'
    '1.985 1.933-2.618.212-.141.413-.251.601-.33.215-.089.43-.164.646-'
    '.227a.098.098 0 00.065-.066 4.51 4.51 0 01.829-1.615 4.535 4.535 0 '
    '011.837-1.388zm3.482 10.565a.637.637 0 000 1.272h3.636a.637.637 0 '
    '100-1.272h-3.636zM8.462 9.23a.637.637 0 00-1.106.631l1.272 2.224-'
    '1.266 2.136a.636.636 0 101.095.649l1.454-2.455a.636.636 0 00.005-.64'
    'L8.462 9.23z" fill="currentColor"></path>'
    '</svg>';

const String _chatAppBarModeMenuClosedIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M3 5h8"/>'
    '<path d="M3 12h8"/>'
    '<path d="M3 19h8"/>'
    '<path d="m15 8 3-3 3 3"/>'
    '<path d="m15 16 3 3 3-3"/>'
    '</svg>';

const String _chatAppBarModeMenuOpenIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M3 5h8"/>'
    '<path d="M3 12h8"/>'
    '<path d="M3 19h8"/>'
    '<path d="m15 5 3 3 3-3"/>'
    '<path d="m15 19 3-3 3 3"/>'
    '</svg>';

const String _chatAppBarPureChatIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202'
    'A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z"/>'
    '</svg>';

const List<Color> _kDarkChatAccentGradient = <Color>[
  Color(0xFFAA9774),
  Color(0xFF8FA38A),
];

const double _kChatAppBarMenuButtonSize = 50;
const double _kChatAppBarAccessoryButtonSize = 40;
const double _kChatAppBarAccessoryGap = 12;
const double _kChatAppBarIslandMaxWidth = 176;
const double _kChatAppBarRightActionSlotWidth = 50;

enum ChatSurfaceMode { workspace, normal, openclaw }

const List<ChatSurfaceMode> kVisibleChatSurfaceModes = <ChatSurfaceMode>[
  ChatSurfaceMode.normal,
  ChatSurfaceMode.workspace,
];

/// 聊天页面 AppBar
class ChatAppBar extends StatelessWidget {
  final VoidCallback onMenuTap;
  final VoidCallback? onPureChatToggleTap;
  final VoidCallback? onCodexTap;
  final VoidCallback onCompanionTap;
  final ChatSurfaceMode activeMode;
  final ValueChanged<ChatSurfaceMode> onModeChanged;
  final String? activeModelId;
  final ValueChanged<BuildContext>? onModelTap;
  final ChatIslandDisplayLayer displayLayer;
  final VoidCallback? onInteracted;
  final ValueChanged<ChatIslandDisplayLayer> onDisplayLayerChanged;
  final ValueChanged<BuildContext> onTerminalEnvironmentTap;
  final VoidCallback onTerminalTap;
  final VoidCallback onBrowserTap;
  final bool hasTerminalEnvironment;
  final bool isBrowserEnabled;
  final String? activeToolType;
  final bool isCompanionModeEnabled;
  final bool isCompanionToggleLoading;
  final bool isCodexReady;
  final bool isCodexConnected;
  final bool isCodexLoading;
  final bool isCodexSelected;
  final bool showAppUpdateIndicator;
  final VoidCallback? onAppUpdateTap;
  final String? appUpdateTooltip;
  final bool translucent;
  final AppBackgroundVisualProfile visualProfile;
  final bool showMenuButton;
  final bool showSurfaceSwitcher;
  final bool showPureChatToggle;
  final bool isPureChatSelected;
  final bool isPureChatToggleLocked;

  const ChatAppBar({
    super.key,
    required this.onMenuTap,
    this.onPureChatToggleTap,
    this.onCodexTap,
    required this.onCompanionTap,
    required this.activeMode,
    required this.onModeChanged,
    this.activeModelId,
    this.onModelTap,
    this.displayLayer = ChatIslandDisplayLayer.mode,
    this.onInteracted,
    required this.onDisplayLayerChanged,
    required this.onTerminalEnvironmentTap,
    required this.onTerminalTap,
    required this.onBrowserTap,
    this.hasTerminalEnvironment = false,
    this.isBrowserEnabled = false,
    this.activeToolType,
    this.isCompanionModeEnabled = false,
    this.isCompanionToggleLoading = false,
    this.isCodexReady = false,
    this.isCodexConnected = false,
    this.isCodexLoading = false,
    this.isCodexSelected = false,
    this.showAppUpdateIndicator = false,
    this.onAppUpdateTap,
    this.appUpdateTooltip,
    this.translucent = false,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.showMenuButton = true,
    this.showSurfaceSwitcher = true,
    this.showPureChatToggle = false,
    this.isPureChatSelected = false,
    this.isPureChatToggleLocked = true,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final iconTint = translucent
        ? visualProfile.appBarIconColor
        : context.isDarkTheme
        ? palette.textPrimary
        : Colors.grey[800]!;
    const updateTint = Color(0xFFD4A017);
    return ColoredBox(
      color: translucent ? Colors.transparent : palette.pageBackground,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: SizedBox(
          height: 50,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final leftReservedSpace =
                  (showMenuButton ? _kChatAppBarMenuButtonSize : 0) +
                  _kChatAppBarAccessoryButtonSize +
                  _kChatAppBarAccessoryGap * 2;
              final rightActionCount =
                  (showAppUpdateIndicator ? 1 : 0) +
                  (showPureChatToggle ? 1 : 0);
              final rightReservedSpace =
                  rightActionCount * _kChatAppBarRightActionSlotWidth +
                  _kChatAppBarAccessoryGap;
              final symmetricReservedSpace = math.max(
                leftReservedSpace,
                rightReservedSpace,
              );
              final islandWidth = math
                  .min(
                    _kChatAppBarIslandMaxWidth,
                    math.max(
                      0,
                      constraints.maxWidth - symmetricReservedSpace * 2,
                    ),
                  )
                  .toDouble();
              final islandCenterX = constraints.maxWidth / 2;
              final islandLeft = islandCenterX - islandWidth / 2;
              final accessoryLeftEdge = showMenuButton
                  ? _kChatAppBarMenuButtonSize + _kChatAppBarAccessoryGap
                  : _kChatAppBarAccessoryGap;
              final accessoryRightEdge = islandLeft - _kChatAppBarAccessoryGap;
              final maxCompanionLeft =
                  accessoryRightEdge - _kChatAppBarAccessoryButtonSize;
              final centeredCompanionLeft =
                  accessoryLeftEdge +
                  ((accessoryRightEdge -
                              accessoryLeftEdge -
                              _kChatAppBarAccessoryButtonSize) /
                          2)
                      .clamp(0, double.infinity)
                      .toDouble();
              final companionLeft = maxCompanionLeft >= accessoryLeftEdge
                  ? centeredCompanionLeft
                        .clamp(accessoryLeftEdge, maxCompanionLeft)
                        .toDouble()
                  : accessoryLeftEdge;

              return Stack(
                alignment: Alignment.center,
                children: [
                  if (showMenuButton)
                    Positioned(
                      left: 0,
                      top: 0,
                      bottom: 0,
                      width: _kChatAppBarMenuButtonSize,
                      child: Center(
                        child: GestureDetector(
                          key: const ValueKey('chat-app-bar-menu-button'),
                          onTap: onMenuTap,
                          child: Container(
                            color: Colors.transparent,
                            padding: const EdgeInsets.all(15),
                            child: SvgPicture.asset(
                              'assets/home/drawer_icon.svg',
                              width: 20,
                              height: 20,
                              colorFilter: ColorFilter.mode(
                                iconTint,
                                BlendMode.srcIn,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  Positioned(
                    left: companionLeft,
                    top: 0,
                    bottom: 0,
                    width: _kChatAppBarAccessoryButtonSize,
                    child: Center(
                      child: _ChatAppBarCompanionButton(
                        isEnabled: isCompanionModeEnabled,
                        isLoading: isCompanionToggleLoading,
                        iconTint: iconTint,
                        selectedColor: context.isDarkTheme
                            ? palette.accentPrimary
                            : const Color(0xFF1930D9),
                        onTap: onCompanionTap,
                      ),
                    ),
                  ),
                  Center(
                    child: SizedBox(
                      key: const ValueKey('chat-app-bar-island'),
                      width: islandWidth,
                      child: _ChatModeModelSwitcher(
                        activeMode: activeMode,
                        onModeChanged: onModeChanged,
                        activeModelId: activeModelId,
                        onModelTap: onModelTap,
                        displayLayer: displayLayer,
                        onInteracted: onInteracted,
                        onDisplayLayerChanged: onDisplayLayerChanged,
                        onTerminalEnvironmentTap: onTerminalEnvironmentTap,
                        onTerminalTap: onTerminalTap,
                        onBrowserTap: onBrowserTap,
                        hasTerminalEnvironment: hasTerminalEnvironment,
                        isBrowserEnabled: isBrowserEnabled,
                        activeToolType: activeToolType,
                        translucent: translucent,
                        visualProfile: visualProfile,
                        showSurfaceLayer: showSurfaceSwitcher,
                      ),
                    ),
                  ),
                  Align(
                    alignment: Alignment.centerRight,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (showAppUpdateIndicator)
                          GestureDetector(
                            key: const ValueKey('chat-app-update-button'),
                            onTap: onAppUpdateTap,
                            child: Tooltip(
                              message: appUpdateTooltip ?? '发现新版本',
                              child: Container(
                                color: Colors.transparent,
                                padding: const EdgeInsets.all(15),
                                child: SvgPicture.string(
                                  _chatAppBarUpdateSparklesSvg,
                                  width: 18,
                                  height: 18,
                                  colorFilter: const ColorFilter.mode(
                                    updateTint,
                                    BlendMode.srcIn,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        if (showPureChatToggle)
                          SizedBox(
                            width: _kChatAppBarRightActionSlotWidth,
                            height: _kChatAppBarRightActionSlotWidth,
                            child: Center(
                              child: _ChatAppBarModeShortcutButton(
                                key: const ValueKey(
                                  'chat-app-bar-pure-chat-button',
                                ),
                                iconTint: iconTint,
                                isCodexLoading: isCodexLoading,
                                isCodexSelected: isCodexSelected,
                                isPureChatSelected: isPureChatSelected,
                                isPureChatToggleLocked: isPureChatToggleLocked,
                                onCodexTap: onCodexTap,
                                onPureChatToggleTap: onPureChatToggleTap,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

enum _ChatAppBarModeShortcutAction { codex, pureChat }

class _ChatAppBarCompanionButton extends StatelessWidget {
  const _ChatAppBarCompanionButton({
    required this.isEnabled,
    required this.isLoading,
    required this.iconTint,
    required this.selectedColor,
    required this.onTap,
  });

  final bool isEnabled;
  final bool isLoading;
  final Color iconTint;
  final Color selectedColor;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = isEnabled ? selectedColor : iconTint;
    return GestureDetector(
      key: const ValueKey('chat-app-companion-button'),
      onTap: isLoading ? null : onTap,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: _kChatAppBarAccessoryButtonSize,
        height: _kChatAppBarAccessoryButtonSize,
        child: Center(
          child: isLoading
              ? SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    valueColor: AlwaysStoppedAnimation<Color>(color),
                  ),
                )
              : SvgPicture.asset(
                  'assets/home/avatar.svg',
                  width: 20,
                  height: 20,
                  colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
                ),
        ),
      ),
    );
  }
}

class _ChatAppBarModeShortcutButton extends StatefulWidget {
  const _ChatAppBarModeShortcutButton({
    super.key,
    required this.iconTint,
    required this.isCodexLoading,
    required this.isCodexSelected,
    required this.isPureChatSelected,
    required this.isPureChatToggleLocked,
    required this.onCodexTap,
    required this.onPureChatToggleTap,
  });

  final Color iconTint;
  final bool isCodexLoading;
  final bool isCodexSelected;
  final bool isPureChatSelected;
  final bool isPureChatToggleLocked;
  final VoidCallback? onCodexTap;
  final VoidCallback? onPureChatToggleTap;

  @override
  State<_ChatAppBarModeShortcutButton> createState() =>
      _ChatAppBarModeShortcutButtonState();
}

class _ChatAppBarModeShortcutButtonState
    extends State<_ChatAppBarModeShortcutButton> {
  bool _isOpen = false;

  Future<void> _openMenu() async {
    if (_isOpen) {
      return;
    }
    final buttonBox = context.findRenderObject() as RenderBox?;
    final overlayBox =
        Navigator.of(context).overlay?.context.findRenderObject() as RenderBox?;
    if (buttonBox == null || overlayBox == null) {
      return;
    }

    setState(() => _isOpen = true);
    final buttonOffset = buttonBox.localToGlobal(
      Offset.zero,
      ancestor: overlayBox,
    );
    final buttonRect = buttonOffset & buttonBox.size;
    final menuAnchorRect = Rect.fromLTWH(
      buttonRect.left,
      buttonRect.bottom + 4,
      buttonRect.width,
      buttonRect.height,
    );
    final action = await showMenu<_ChatAppBarModeShortcutAction>(
      context: context,
      position: RelativeRect.fromRect(
        menuAnchorRect,
        Offset.zero & overlayBox.size,
      ),
      color: Colors.transparent,
      shadowColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      constraints: const BoxConstraints(minWidth: 40, maxWidth: 40),
      items: _buildMenuItems(context),
    );
    if (mounted) {
      setState(() => _isOpen = false);
    }
    switch (action) {
      case _ChatAppBarModeShortcutAction.codex:
        widget.onCodexTap?.call();
        break;
      case _ChatAppBarModeShortcutAction.pureChat:
        widget.onPureChatToggleTap?.call();
        break;
      case null:
        break;
    }
  }

  List<PopupMenuEntry<_ChatAppBarModeShortcutAction>> _buildMenuItems(
    BuildContext context,
  ) {
    final palette = context.omniPalette;
    final selectedColor = palette.accentPrimary;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    final canSelectPureChat =
        widget.isCodexSelected ||
        (!widget.isPureChatToggleLocked && widget.onPureChatToggleTap != null);
    return <PopupMenuEntry<_ChatAppBarModeShortcutAction>>[
      PopupMenuItem<_ChatAppBarModeShortcutAction>(
        key: const ValueKey('chat-app-bar-mode-menu-codex'),
        value: _ChatAppBarModeShortcutAction.codex,
        enabled: !widget.isCodexLoading && widget.onCodexTap != null,
        height: 40,
        padding: EdgeInsets.zero,
        child: _ChatAppBarModeShortcutMenuIcon(
          iconSvg: _chatAppBarCodexIconSvg,
          tooltip: isEnglish ? 'Codex mode' : 'Codex 模式',
          selected: widget.isCodexSelected,
          selectedColor: selectedColor,
          iconTint: widget.iconTint,
        ),
      ),
      PopupMenuItem<_ChatAppBarModeShortcutAction>(
        key: const ValueKey('chat-app-bar-mode-menu-pure-chat'),
        value: _ChatAppBarModeShortcutAction.pureChat,
        enabled: canSelectPureChat,
        height: 40,
        padding: EdgeInsets.zero,
        child: _ChatAppBarModeShortcutMenuIcon(
          iconSvg: _chatAppBarPureChatIconSvg,
          tooltip: isEnglish ? 'Pure chat' : '纯聊天模式',
          selected: widget.isPureChatSelected,
          selectedColor: selectedColor,
          iconTint: canSelectPureChat
              ? widget.iconTint
              : widget.iconTint.withValues(alpha: 0.42),
        ),
      ),
    ];
  }

  String _closedIconSvg() {
    if (widget.isCodexSelected) {
      return _chatAppBarCodexIconSvg;
    }
    if (widget.isPureChatSelected) {
      return _chatAppBarPureChatIconSvg;
    }
    return _chatAppBarModeMenuClosedIconSvg;
  }

  Widget _buildClosedIcon(Color color) {
    if (widget.isCodexLoading && !widget.isCodexSelected) {
      return SizedBox(
        width: 18,
        height: 18,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          valueColor: AlwaysStoppedAnimation<Color>(color),
        ),
      );
    }
    return SvgPicture.string(
      _closedIconSvg(),
      width: 20,
      height: 20,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }

  Widget _buildOpenIcon(Color color) {
    return SvgPicture.string(
      _chatAppBarModeMenuOpenIconSvg,
      width: 20,
      height: 20,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selectedColor = palette.accentPrimary;
    final hasSelectedMode = widget.isCodexSelected || widget.isPureChatSelected;
    final effectiveIconColor = _isOpen || hasSelectedMode
        ? selectedColor
        : widget.iconTint;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    return Tooltip(
      message: _isOpen
          ? (isEnglish ? 'Close mode menu' : '收起模式菜单')
          : (isEnglish ? 'Switch chat mode' : '切换聊天模式'),
      child: GestureDetector(
        onTap: _openMenu,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: _kChatAppBarAccessoryButtonSize,
          height: _kChatAppBarAccessoryButtonSize,
          child: Center(
            child: _isOpen
                ? _buildOpenIcon(effectiveIconColor)
                : _buildClosedIcon(effectiveIconColor),
          ),
        ),
      ),
    );
  }
}

class _ChatAppBarModeShortcutMenuIcon extends StatelessWidget {
  const _ChatAppBarModeShortcutMenuIcon({
    required this.iconSvg,
    required this.tooltip,
    required this.selected,
    required this.selectedColor,
    required this.iconTint,
  });

  final String iconSvg;
  final String tooltip;
  final bool selected;
  final Color selectedColor;
  final Color iconTint;

  @override
  Widget build(BuildContext context) {
    final color = selected ? selectedColor : iconTint;
    return Tooltip(
      message: tooltip,
      child: SizedBox(
        width: 40,
        height: 40,
        child: Center(
          child: SvgPicture.string(
            iconSvg,
            width: 18,
            height: 18,
            colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
          ),
        ),
      ),
    );
  }
}

class _ChatModeModelSwitcher extends StatefulWidget {
  const _ChatModeModelSwitcher({
    required this.activeMode,
    required this.onModeChanged,
    this.activeModelId,
    this.onModelTap,
    required this.displayLayer,
    this.onInteracted,
    required this.onDisplayLayerChanged,
    required this.onTerminalEnvironmentTap,
    required this.onTerminalTap,
    required this.onBrowserTap,
    required this.hasTerminalEnvironment,
    required this.isBrowserEnabled,
    this.activeToolType,
    this.translucent = false,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.showSurfaceLayer = true,
  });

  final ChatSurfaceMode activeMode;
  final ValueChanged<ChatSurfaceMode> onModeChanged;
  final String? activeModelId;
  final ValueChanged<BuildContext>? onModelTap;
  final ChatIslandDisplayLayer displayLayer;
  final VoidCallback? onInteracted;
  final ValueChanged<ChatIslandDisplayLayer> onDisplayLayerChanged;
  final ValueChanged<BuildContext> onTerminalEnvironmentTap;
  final VoidCallback onTerminalTap;
  final VoidCallback onBrowserTap;
  final bool hasTerminalEnvironment;
  final bool isBrowserEnabled;
  final String? activeToolType;
  final bool translucent;
  final AppBackgroundVisualProfile visualProfile;
  final bool showSurfaceLayer;

  @override
  State<_ChatModeModelSwitcher> createState() => _ChatModeModelSwitcherState();
}

class _ChatModeModelSwitcherState extends State<_ChatModeModelSwitcher> {
  static const String _terminalIconSvg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
      'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
      'stroke-linecap="round" stroke-linejoin="round">'
      '<path d="m7 11 2-2-2-2"/>'
      '<path d="M11 13h4"/>'
      '<rect width="18" height="18" x="3" y="3" rx="2" ry="2"/>'
      '</svg>';
  static const String _browserIconSvg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
      'fill="none" viewBox="0 0 24 24">'
      '<path stroke="currentColor" stroke-linecap="round" '
      'stroke-linejoin="round" '
      'd="M12 8C9.79086 8 8 9.79086 8 12C8 12.7286 8.19479 13.4117 8.53513 14'
      'M12 8C14.2091 8 16 9.79086 16 12C16 13.0144 15.6224 13.9407 15 14.6458'
      'M12 8H20.0645M15 14.6458C14.2671 15.4762 13.1947 16 12 16C10.5194 16 '
      '9.22675 15.1956 8.53513 14M15 14.6458L10.7394 20.9124'
      'M8.53513 14L4.36907 7.22607M4.36907 7.22607C3.50156 8.60982 3 10.2463 '
      '3 12C3 16.5427 6.36566 20.2994 10.7394 20.9124M4.36907 7.22607'
      'C5.9604 4.68775 8.7831 3 12 3C16.9706 3 21 7.02944 21 12C21 16.9706 '
      '16.9706 21 12 21C11.5722 21 11.1513 20.9702 10.7394 20.9124"/>'
      '</svg>';
  static const String _environmentIconSvg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
      'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
      'stroke-linecap="round" stroke-linejoin="round">'
      '<path d="M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1"/>'
      '<path d="M16 21h1a2 2 0 0 0 2-2v-5c0-1.1.9-2 2-2a2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1"/>'
      '</svg>';
  static const Duration _switchDuration = Duration(milliseconds: 460);
  static const double _verticalSwitchThreshold = 10;
  static const double _verticalVelocityThreshold = 240;
  static const double _switcherHeight = 32;
  static const double _offstageLayerGap = 2;

  double _verticalDragDelta = 0;
  double _horizontalDragDelta = 0;

  int get _activeVisibleModeIndex {
    final index = kVisibleChatSurfaceModes.indexOf(widget.activeMode);
    if (index >= 0) {
      return index;
    }
    return 0;
  }

  String get _modelLabel {
    final text = (widget.activeModelId ?? '').trim();
    if (text.isEmpty) {
      return LegacyTextLocalizer.isEnglish ? 'No model set' : '未设置模型';
    }
    return text;
  }

  bool get _canRevealModelLabel =>
      widget.activeMode == ChatSurfaceMode.normal &&
      (widget.activeModelId ?? '').trim().isNotEmpty;

  List<ChatIslandDisplayLayer> get _visibleLayers => widget.showSurfaceLayer
      ? const <ChatIslandDisplayLayer>[
          ChatIslandDisplayLayer.tools,
          ChatIslandDisplayLayer.model,
          ChatIslandDisplayLayer.mode,
        ]
      : const <ChatIslandDisplayLayer>[
          ChatIslandDisplayLayer.tools,
          ChatIslandDisplayLayer.model,
        ];

  ChatIslandDisplayLayer get _effectiveDisplayLayer =>
      _visibleLayers.contains(widget.displayLayer)
      ? widget.displayLayer
      : ChatIslandDisplayLayer.model;

  int _layerOrder(ChatIslandDisplayLayer layer) =>
      _visibleLayers.indexOf(layer);

  void _handleSliderInteraction() {
    widget.onInteracted?.call();
  }

  void _handleHorizontalDragUpdate(DragUpdateDetails details) {
    if (!widget.showSurfaceLayer ||
        widget.activeMode != ChatSurfaceMode.normal ||
        widget.displayLayer != ChatIslandDisplayLayer.model) {
      return;
    }
    _horizontalDragDelta += details.delta.dx;
  }

  void _handleHorizontalDragEnd(DragEndDetails details) {
    if (!widget.showSurfaceLayer ||
        widget.activeMode != ChatSurfaceMode.normal ||
        widget.displayLayer != ChatIslandDisplayLayer.model) {
      _horizontalDragDelta = 0;
      return;
    }
    final velocity = details.primaryVelocity ?? 0;
    final shouldSwitch =
        _horizontalDragDelta.abs() > 14 || velocity.abs() > 250;
    if (!shouldSwitch) {
      _horizontalDragDelta = 0;
      return;
    }
    final intent = _horizontalDragDelta + velocity * 0.015;
    _horizontalDragDelta = 0;
    final currentIndex = _activeVisibleModeIndex;
    final delta = intent > 0 ? 1 : -1;
    final targetIndex = (currentIndex + delta).clamp(
      0,
      kVisibleChatSurfaceModes.length - 1,
    );
    widget.onInteracted?.call();
    widget.onModeChanged(kVisibleChatSurfaceModes[targetIndex]);
  }

  void _handleVerticalDragUpdate(DragUpdateDetails details) {
    _verticalDragDelta += details.delta.dy;
  }

  void _handleVerticalDragEnd(DragEndDetails details) {
    final velocity = details.primaryVelocity ?? 0;
    final shouldToggle =
        _verticalDragDelta.abs() > _verticalSwitchThreshold ||
        velocity.abs() > _verticalVelocityThreshold;
    if (!shouldToggle) {
      _verticalDragDelta = 0;
      return;
    }
    final intent = _verticalDragDelta + velocity * 0.015;
    _verticalDragDelta = 0;

    if (widget.activeMode != ChatSurfaceMode.normal) {
      return;
    }
    widget.onInteracted?.call();
    if (intent > 0) {
      if (_effectiveDisplayLayer != ChatIslandDisplayLayer.tools) {
        widget.onDisplayLayerChanged(ChatIslandDisplayLayer.tools);
      }
      return;
    }
    if ((_canRevealModelLabel || !widget.showSurfaceLayer) &&
        _effectiveDisplayLayer != ChatIslandDisplayLayer.model) {
      widget.onDisplayLayerChanged(ChatIslandDisplayLayer.model);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final restingLabelColor = widget.translucent
        ? widget.visualProfile.subtleTextColor
        : context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF9DA9BB);
    final islandBaseColor = widget.translucent
        ? palette.surfacePrimary
        : context.isDarkTheme
        ? palette.surfaceSecondary
        : palette.surfacePrimary;
    final modelLabelWidget = Builder(
      builder: (anchorContext) {
        final text = Text(
          _modelLabel,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 14,
            color: restingLabelColor,
            fontWeight: FontWeight.w500,
          ),
        );
        if (widget.onModelTap == null) {
          return Center(child: text);
        }
        return InkWell(
          onTap: () {
            widget.onInteracted?.call();
            widget.onModelTap?.call(anchorContext);
          },
          borderRadius: BorderRadius.circular(999),
          child: Center(child: text),
        );
      },
    );
    final toolLayerWidget = _ChatToolSlider(
      environmentIconSvg: _environmentIconSvg,
      terminalIconSvg: _terminalIconSvg,
      browserIconSvg: _browserIconSvg,
      activeToolType: widget.activeToolType,
      hasTerminalEnvironment: widget.hasTerminalEnvironment,
      onTerminalEnvironmentTap: (anchorContext) {
        widget.onInteracted?.call();
        widget.onTerminalEnvironmentTap(anchorContext);
      },
      isBrowserEnabled: widget.isBrowserEnabled,
      onTerminalTap: () {
        widget.onInteracted?.call();
        widget.onTerminalTap();
      },
      onBrowserTap: () {
        widget.onInteracted?.call();
        widget.onBrowserTap();
      },
      onInteracted: _handleSliderInteraction,
      visualProfile: widget.visualProfile,
    );
    final currentOrder = _layerOrder(_effectiveDisplayLayer);

    double topFor(ChatIslandDisplayLayer layer) {
      final delta = _layerOrder(layer) - currentOrder;
      if (delta == 0) return 0;
      final direction = delta > 0 ? 1 : -1;
      return delta * _switcherHeight + direction * _offstageLayerGap;
    }

    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: widget.translucent,
          baseColor: islandBaseColor,
          opacity: 0.78,
        ),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: widget.translucent
              ? widget.visualProfile.islandBorderColor
              : palette.borderSubtle.withValues(alpha: 0.72),
        ),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        child: SizedBox(
          height: _switcherHeight,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onHorizontalDragUpdate: _handleHorizontalDragUpdate,
            onHorizontalDragEnd: _handleHorizontalDragEnd,
            onVerticalDragUpdate: _handleVerticalDragUpdate,
            onVerticalDragEnd: _handleVerticalDragEnd,
            onVerticalDragCancel: () {
              _verticalDragDelta = 0;
            },
            child: Stack(
              fit: StackFit.expand,
              clipBehavior: Clip.hardEdge,
              children: [
                AnimatedPositioned(
                  duration: _switchDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  left: 0,
                  right: 0,
                  height: _switcherHeight,
                  top: topFor(ChatIslandDisplayLayer.mode),
                  child: widget.showSurfaceLayer
                      ? ClipRect(
                          child: ChatModeSlider(
                            activeMode: widget.activeMode,
                            onChanged: widget.onModeChanged,
                            onInteracted: _handleSliderInteraction,
                            visualProfile: widget.visualProfile,
                          ),
                        )
                      : const SizedBox.shrink(),
                ),
                AnimatedPositioned(
                  duration: _switchDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  left: 0,
                  right: 0,
                  height: _switcherHeight,
                  top: topFor(ChatIslandDisplayLayer.model),
                  child: modelLabelWidget,
                ),
                AnimatedPositioned(
                  duration: _switchDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  left: 0,
                  right: 0,
                  height: _switcherHeight,
                  top: topFor(ChatIslandDisplayLayer.tools),
                  child: ClipRect(child: toolLayerWidget),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatToolSlider extends StatelessWidget {
  final String environmentIconSvg;
  final String terminalIconSvg;
  final String browserIconSvg;
  final String? activeToolType;
  final bool hasTerminalEnvironment;
  final ValueChanged<BuildContext> onTerminalEnvironmentTap;
  final bool isBrowserEnabled;
  final VoidCallback onTerminalTap;
  final VoidCallback onBrowserTap;
  final VoidCallback? onInteracted;
  final AppBackgroundVisualProfile visualProfile;

  const _ChatToolSlider({
    required this.environmentIconSvg,
    required this.terminalIconSvg,
    required this.browserIconSvg,
    this.activeToolType,
    required this.hasTerminalEnvironment,
    required this.onTerminalEnvironmentTap,
    this.isBrowserEnabled = false,
    required this.onTerminalTap,
    required this.onBrowserTap,
    this.onInteracted,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
  });

  bool get _isBrowserActive => activeToolType?.trim() == 'browser';
  bool get _isTerminalActive => !_isBrowserActive;

  Alignment get _activeAlignment =>
      _isBrowserActive ? Alignment.centerRight : Alignment.center;

  @override
  Widget build(BuildContext context) {
    final activeGradient = context.isDarkTheme
        ? _kDarkChatAccentGradient
        : const <Color>[Color(0xFF2DA5F0), Color(0xFF1930D9)];
    return SizedBox(
      height: 32,
      child: Container(
        height: 32,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Stack(
          children: [
            AnimatedAlign(
              duration: const Duration(milliseconds: 280),
              curve: Curves.easeOutCubic,
              alignment: _activeAlignment,
              child: FractionallySizedBox(
                widthFactor: 1 / 3,
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 1),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: activeGradient,
                    ),
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
            ),
            Row(
              children: [
                Expanded(child: _buildEnvironmentButton(context)),
                Expanded(
                  child: _buildToolSegment(
                    context: context,
                    key: const ValueKey('chat-island-terminal-button'),
                    isSelected: _isTerminalActive,
                    isEnabled: true,
                    tooltip: LegacyTextLocalizer.isEnglish
                        ? 'Open terminal'
                        : '打开终端',
                    onTap: onTerminalTap,
                    child: SvgPicture.string(
                      terminalIconSvg,
                      width: 16,
                      height: 16,
                    ),
                  ),
                ),
                Expanded(
                  child: _buildToolSegment(
                    context: context,
                    key: const ValueKey('chat-island-browser-button'),
                    isSelected: _isBrowserActive,
                    isEnabled: isBrowserEnabled,
                    tooltip: isBrowserEnabled
                        ? (LegacyTextLocalizer.isEnglish
                              ? 'Open browser for current session'
                              : '打开当前会话浏览器')
                        : (LegacyTextLocalizer.isEnglish
                              ? 'No browser session available'
                              : '当前会话还没有可用的浏览器会话'),
                    onTap: onBrowserTap,
                    child: SvgPicture.string(
                      browserIconSvg,
                      width: 16,
                      height: 16,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEnvironmentButton(BuildContext context) {
    final inactiveColor = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : visualProfile.secondaryTextColor;
    return Builder(
      builder: (anchorContext) {
        return Tooltip(
          message: LegacyTextLocalizer.isEnglish
              ? 'Manage terminal environment variables'
              : '管理终端环境变量',
          child: InkWell(
            key: const ValueKey('chat-island-terminal-env-button'),
            onTap: () {
              onInteracted?.call();
              onTerminalEnvironmentTap(anchorContext);
            },
            borderRadius: BorderRadius.circular(999),
            child: SizedBox.expand(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOutCubic,
                margin: const EdgeInsets.symmetric(horizontal: 1),
                decoration: BoxDecoration(
                  color: Colors.transparent,
                  borderRadius: BorderRadius.circular(999),
                ),
                alignment: Alignment.center,
                child: ColorFiltered(
                  colorFilter: ColorFilter.mode(inactiveColor, BlendMode.srcIn),
                  child: SvgPicture.string(
                    environmentIconSvg,
                    width: 15,
                    height: 15,
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildToolSegment({
    required BuildContext context,
    required Key key,
    required bool isSelected,
    required bool isEnabled,
    required String tooltip,
    required VoidCallback onTap,
    required Widget child,
  }) {
    final inactiveColor = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : visualProfile.secondaryTextColor;
    final color = !isEnabled
        ? inactiveColor.withValues(alpha: 0.72)
        : isSelected
        ? Theme.of(context).colorScheme.onPrimary
        : inactiveColor;
    return Tooltip(
      message: tooltip,
      child: InkWell(
        key: key,
        onTap: isEnabled
            ? () {
                onInteracted?.call();
                onTap();
              }
            : null,
        borderRadius: BorderRadius.circular(999),
        child: Center(
          child: AnimatedScale(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            scale: isSelected ? 1 : 0.95,
            child: ColorFiltered(
              colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}

class ChatModeSlider extends StatefulWidget {
  final ChatSurfaceMode activeMode;
  final ValueChanged<ChatSurfaceMode> onChanged;
  final VoidCallback? onInteracted;
  final AppBackgroundVisualProfile visualProfile;

  const ChatModeSlider({
    super.key,
    required this.activeMode,
    required this.onChanged,
    this.onInteracted,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
  });

  @override
  State<ChatModeSlider> createState() => _ChatModeSliderState();
}

class _ChatModeSliderState extends State<ChatModeSlider> {
  static const String _workspaceIconSvg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
      'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
      'stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-folders-icon lucide-folders">'
      '<path d="M20 5a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h2.5a1.5 1.5 0 0 1 1.2.6l.6.8a1.5 1.5 0 0 0 1.2.6z"/>'
      '<path d="M3 8.268a2 2 0 0 0-1 1.738V19a2 2 0 0 0 2 2h11a2 2 0 0 0 1.732-1"/>'
      '</svg>';

  static const String _normalChatIconSvg = _chatAppBarAgentIconSvg;

  double _dragDelta = 0;

  int get _activeVisibleModeIndex {
    final index = kVisibleChatSurfaceModes.indexOf(widget.activeMode);
    if (index >= 0) {
      return index;
    }
    return 0;
  }

  void _handleDragEnd({double velocity = 0}) {
    final intent = _dragDelta + velocity * 0.015;
    final shouldSwitch = _dragDelta.abs() > 14 || velocity.abs() > 250;
    if (shouldSwitch) {
      final currentIndex = _activeVisibleModeIndex;
      final delta = intent > 0 ? 1 : -1;
      final targetIndex = (currentIndex + delta).clamp(
        0,
        kVisibleChatSurfaceModes.length - 1,
      );
      widget.onChanged(kVisibleChatSurfaceModes[targetIndex]);
    }
    _dragDelta = 0;
  }

  @override
  Widget build(BuildContext context) {
    final activeGradient = context.isDarkTheme
        ? _kDarkChatAccentGradient
        : const <Color>[Color(0xFF2DA5F0), Color(0xFF1930D9)];
    final alignment = _activeVisibleModeIndex == 0
        ? Alignment.centerLeft
        : Alignment.centerRight;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onHorizontalDragUpdate: (details) {
        _dragDelta += details.delta.dx;
        widget.onInteracted?.call();
      },
      onHorizontalDragEnd: (details) {
        widget.onInteracted?.call();
        _handleDragEnd(velocity: details.primaryVelocity ?? 0);
      },
      onTapUp: (details) {
        widget.onInteracted?.call();
        final box = context.findRenderObject() as RenderBox?;
        if (box == null || !box.hasSize) return;
        final local = box.globalToLocal(details.globalPosition);
        final segmentWidth = box.size.width / kVisibleChatSurfaceModes.length;
        final targetIndex = (local.dx / segmentWidth).floor().clamp(
          0,
          kVisibleChatSurfaceModes.length - 1,
        );
        widget.onChanged(kVisibleChatSurfaceModes[targetIndex]);
      },
      child: Container(
        height: 32,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Stack(
          children: [
            AnimatedAlign(
              duration: const Duration(milliseconds: 280),
              curve: Curves.easeOutCubic,
              alignment: alignment,
              child: FractionallySizedBox(
                widthFactor: 1 / kVisibleChatSurfaceModes.length,
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 1),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: activeGradient,
                    ),
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
            ),
            Row(
              children: [
                Expanded(
                  child: _buildModeIcon(
                    isSelected: widget.activeMode == ChatSurfaceMode.normal,
                    child: SvgPicture.string(
                      _normalChatIconSvg,
                      width: 16,
                      height: 16,
                    ),
                  ),
                ),
                Expanded(
                  child: _buildModeIcon(
                    isSelected: widget.activeMode == ChatSurfaceMode.workspace,
                    child: SvgPicture.string(
                      _workspaceIconSvg,
                      width: 16,
                      height: 16,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildModeIcon({required bool isSelected, required Widget child}) {
    final inactiveColor = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : widget.visualProfile.secondaryTextColor;
    final color = isSelected
        ? Theme.of(context).colorScheme.onPrimary
        : inactiveColor;
    return Center(
      child: AnimatedScale(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        scale: isSelected ? 1 : 0.95,
        child: ColorFiltered(
          colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
          child: child,
        ),
      ),
    );
  }
}

/// 消息列表
class ChatMessageList extends StatefulWidget {
  final List<ChatMessageModel> messages;
  final ScrollController scrollController;
  final Future<void> Function() onBeforeTaskExecute;
  final void Function(String taskId)? onCancelTask;
  final void Function(List<String> requiredPermissionIds)? onRequestAuthorize;
  final double bottomOverlayInset;
  final void Function(ChatMessageModel message, LongPressStartDetails details)?
  onUserMessageLongPressStart;
  final String? editingUserMessageId;
  final TextEditingController? userMessageEditController;
  final VoidCallback? onUserMessageEditCancelled;
  final ValueChanged<ChatMessageModel>? onUserMessageEditSaved;
  final Future<void> Function()? onLoadMore;
  final bool hasMore;
  final Set<String> activeAgentTaskIds;
  final Set<String>? expandedAgentRunTaskIds;
  final ValueChanged<Set<String>>? onExpandedAgentRunTaskIdsChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  const ChatMessageList({
    super.key,
    required this.messages,
    required this.scrollController,
    required this.onBeforeTaskExecute,
    this.onCancelTask,
    this.onRequestAuthorize,
    this.bottomOverlayInset = 0,
    this.onUserMessageLongPressStart,
    this.editingUserMessageId,
    this.userMessageEditController,
    this.onUserMessageEditCancelled,
    this.onUserMessageEditSaved,
    this.onLoadMore,
    this.hasMore = false,
    this.activeAgentTaskIds = const <String>{},
    this.expandedAgentRunTaskIds,
    this.onExpandedAgentRunTaskIdsChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
  });

  @override
  State<ChatMessageList> createState() => _ChatMessageListState();
}

class _ChatMessageListState extends State<ChatMessageList> {
  static const Duration _kAgentRunToggleAutoStickSuppression = Duration(
    milliseconds: 420,
  );
  bool _stickToBottomScheduled = false;
  bool _autoStickToLatest = true;
  bool _outerScrollWasUserDriven = false;
  bool _isAutoLoadingHistory = false;
  final Set<String> _localExpandedAgentRunTaskIds = <String>{};
  static const double _latestEdgeTolerance = 48.0;
  static const double _manualLatestAttachTolerance = 2.0;
  static const double _historyLoadTriggerExtent = 180.0;
  ObservableChatMessageList? _observableMessages;
  DateTime? _autoStickSuppressedUntil;

  Set<String> get _expandedAgentRunTaskIds =>
      widget.expandedAgentRunTaskIds ?? _localExpandedAgentRunTaskIds;

  bool get _isAutoStickTemporarilySuppressed {
    final suppressedUntil = _autoStickSuppressedUntil;
    if (suppressedUntil == null) {
      return false;
    }
    if (DateTime.now().isBefore(suppressedUntil)) {
      return true;
    }
    _autoStickSuppressedUntil = null;
    return false;
  }

  @override
  void initState() {
    super.initState();
    _bindObservableMessages(widget.messages);
    _scheduleStickToBottom();
  }

  @override
  void didUpdateWidget(covariant ChatMessageList oldWidget) {
    super.didUpdateWidget(oldWidget);
    _bindObservableMessages(widget.messages);
    if (oldWidget.scrollController != widget.scrollController) {
      _autoStickToLatest = true;
      _outerScrollWasUserDriven = false;
      _autoStickSuppressedUntil = null;
    }
    if (_autoStickToLatest) {
      _autoStickToLatest = true;
      _scheduleStickToLatest();
      return;
    }
    if (_isAutoStickTemporarilySuppressed) {
      return;
    }
    if (_isNearLatest(null, _manualLatestAttachTolerance)) {
      _autoStickToLatest = true;
      _scheduleStickToLatest();
    }
  }

  @override
  void dispose() {
    _observableMessages?.removeListener(_handleObservableMessagesChanged);
    super.dispose();
  }

  List<ScrollPosition> _attachedPositions() {
    if (!widget.scrollController.hasClients) {
      return const <ScrollPosition>[];
    }
    return widget.scrollController.positions.toList(growable: false);
  }

  bool _isNearLatest([
    ScrollMetrics? metrics,
    double tolerance = _latestEdgeTolerance,
  ]) {
    final resolvedMetrics = metrics;
    if (resolvedMetrics != null) {
      return _distanceToLatest(resolvedMetrics) <= tolerance;
    }
    final positions = _attachedPositions();
    if (positions.isEmpty) {
      return true;
    }
    return positions.every(
      (position) => _distanceToLatest(position) <= tolerance,
    );
  }

  double _latestOffset(ScrollMetrics metrics) {
    return switch (metrics.axisDirection) {
      AxisDirection.down || AxisDirection.right => metrics.maxScrollExtent,
      AxisDirection.up || AxisDirection.left => metrics.minScrollExtent,
    };
  }

  double _distanceToLatest(ScrollMetrics metrics) {
    return (metrics.pixels - _latestOffset(metrics)).abs();
  }

  void _scheduleStickToBottom() => _scheduleStickToLatest();

  void _scheduleStickToLatest() {
    if (!_autoStickToLatest || _isAutoStickTemporarilySuppressed) {
      return;
    }
    if (_stickToBottomScheduled) {
      return;
    }
    _stickToBottomScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _stickToBottomScheduled = false;
      if (!mounted) {
        if (mounted) {
          _scheduleStickToBottom();
        }
        return;
      }
      final positions = _attachedPositions();
      if (positions.isEmpty) {
        if (mounted) {
          _scheduleStickToBottom();
        }
        return;
      }
      if (!_autoStickToLatest) {
        return;
      }
      for (final position in positions) {
        final target = _latestOffset(position);
        if ((target - position.pixels).abs() < 0.5) {
          continue;
        }
        position.jumpTo(target);
      }
    });
  }

  void _handleStreamingTextLayoutChanged() {
    if (_autoStickToLatest && !_isAutoStickTemporarilySuppressed) {
      _scheduleStickToLatest();
    }
  }

  void _bindObservableMessages(List<ChatMessageModel> messages) {
    final nextObservable = messages is ObservableChatMessageList
        ? messages
        : null;
    if (identical(_observableMessages, nextObservable)) {
      return;
    }
    _observableMessages?.removeListener(_handleObservableMessagesChanged);
    _observableMessages = nextObservable;
    _observableMessages?.addListener(_handleObservableMessagesChanged);
  }

  void _handleObservableMessagesChanged() {
    if (!mounted) {
      return;
    }
    if (_autoStickToLatest && !_isAutoStickTemporarilySuppressed) {
      _scheduleStickToLatest();
    }
    setState(() {});
  }

  void _handleParentScrollHandoff() {
    _autoStickToLatest = false;
    _outerScrollWasUserDriven = false;
  }

  void _suspendAutoStickForAgentRunToggle() {
    _autoStickToLatest = false;
    _outerScrollWasUserDriven = false;
    _autoStickSuppressedUntil = DateTime.now().add(
      _kAgentRunToggleAutoStickSuppression,
    );
  }

  void _toggleAgentRunGroup(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return;
    }
    _suspendAutoStickForAgentRunToggle();
    final nextExpandedTaskIds = Set<String>.from(_expandedAgentRunTaskIds);
    if (nextExpandedTaskIds.contains(normalizedTaskId)) {
      nextExpandedTaskIds.remove(normalizedTaskId);
    } else {
      nextExpandedTaskIds.add(normalizedTaskId);
    }
    if (widget.expandedAgentRunTaskIds != null) {
      widget.onExpandedAgentRunTaskIdsChanged?.call(nextExpandedTaskIds);
    } else {
      setState(() {
        _localExpandedAgentRunTaskIds
          ..clear()
          ..addAll(nextExpandedTaskIds);
      });
      widget.onExpandedAgentRunTaskIdsChanged?.call(nextExpandedTaskIds);
    }
  }

  double _distanceToOldest(ScrollMetrics metrics) {
    return (metrics.pixels - metrics.minScrollExtent).abs();
  }

  ScrollPosition? _closestAttachedPosition({
    required double pixels,
    required double minScrollExtent,
    required double maxScrollExtent,
  }) {
    final positions = _attachedPositions();
    if (positions.isEmpty) {
      return null;
    }
    ScrollPosition? bestMatch;
    var bestScore = double.infinity;
    for (final position in positions) {
      final score =
          (position.pixels - pixels).abs() +
          (position.minScrollExtent - minScrollExtent).abs() +
          (position.maxScrollExtent - maxScrollExtent).abs();
      if (score < bestScore) {
        bestScore = score;
        bestMatch = position;
      }
    }
    return bestMatch;
  }

  void _maybeLoadOlderMessages(ScrollMetrics metrics) {
    if (_isAutoLoadingHistory || !widget.hasMore || widget.onLoadMore == null) {
      return;
    }
    if (_distanceToOldest(metrics) > _historyLoadTriggerExtent) {
      return;
    }
    _isAutoLoadingHistory = true;
    unawaited(
      _loadOlderMessagesAndPreserveViewport(
        anchorPixels: metrics.pixels,
        anchorMinScrollExtent: metrics.minScrollExtent,
        anchorMaxScrollExtent: metrics.maxScrollExtent,
      ),
    );
  }

  Future<void> _loadOlderMessagesAndPreserveViewport({
    required double anchorPixels,
    required double anchorMinScrollExtent,
    required double anchorMaxScrollExtent,
  }) async {
    try {
      await widget.onLoadMore!.call();
      if (!mounted) return;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          _isAutoLoadingHistory = false;
          return;
        }
        final position = _closestAttachedPosition(
          pixels: anchorPixels,
          minScrollExtent: anchorMinScrollExtent,
          maxScrollExtent: anchorMaxScrollExtent,
        );
        if (position == null) {
          _isAutoLoadingHistory = false;
          return;
        }
        final extentDelta = position.maxScrollExtent - anchorMaxScrollExtent;
        if (extentDelta.abs() >= 0.5) {
          final targetOffset = (anchorPixels + extentDelta).clamp(
            position.minScrollExtent,
            position.maxScrollExtent,
          );
          if ((position.pixels - targetOffset).abs() >= 0.5) {
            position.jumpTo(targetOffset);
          }
        }
        _isAutoLoadingHistory = false;
      });
    } catch (_) {
      _isAutoLoadingHistory = false;
      rethrow;
    }
  }

  bool _handleListScrollNotification(ScrollNotification notification) {
    if (notification.depth != 0 || notification.metrics.axis != Axis.vertical) {
      return false;
    }

    final shouldCheckAutoLoad =
        notification is ScrollUpdateNotification ||
        notification is OverscrollNotification ||
        notification is ScrollEndNotification;
    if (shouldCheckAutoLoad) {
      _maybeLoadOlderMessages(notification.metrics);
    }

    final isUserDrivenUpdate =
        (notification is ScrollUpdateNotification &&
            notification.dragDetails != null) ||
        (notification is OverscrollNotification &&
            notification.dragDetails != null);
    if (isUserDrivenUpdate) {
      _outerScrollWasUserDriven = true;
      if (_distanceToLatest(notification.metrics) >
          _manualLatestAttachTolerance) {
        _autoStickToLatest = false;
      }
      return false;
    }
    if (notification is ScrollEndNotification) {
      if (_outerScrollWasUserDriven &&
          _isNearLatest(notification.metrics, _manualLatestAttachTolerance)) {
        _autoStickToLatest = true;
      }
      _outerScrollWasUserDriven = false;
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    final reservedBottomInset = widget.bottomOverlayInset
        .clamp(0.0, double.infinity)
        .toDouble();
    final pageBackgroundColor =
        !widget.appearanceConfig.isActive && context.isDarkTheme
        ? context.omniPalette.pageBackground
        : null;

    final Widget content;
    if (widget.messages.isEmpty) {
      content = GestureDetector(
        onVerticalDragUpdate: (_) {},
        behavior: HitTestBehavior.opaque,
        child: Center(
          child: Text(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'How can I help you?'
                : '有什么可以帮助你的？',
            style: TextStyle(
              color:
                  !widget.appearanceConfig.isActive &&
                      widget.appearanceConfig.chatTextColorMode !=
                          AppBackgroundTextColorMode.custom
                  ? context.omniPalette.textSecondary
                  : widget.visualProfile.secondaryTextColor,
              fontSize: 14,
            ),
          ),
        ),
      );
    } else {
      String? latestUserMessageId;
      final messageSource = _observableMessages ?? widget.messages;
      final timelineEntries = buildAgentRunTimelineEntries(
        List<ChatMessageModel>.from(messageSource),
        activeTaskIds: widget.activeAgentTaskIds,
      );
      for (final item in messageSource) {
        if (item.user == 1) {
          latestUserMessageId = item.id;
          break;
        }
      }
      Widget listView = ListView.builder(
        controller: widget.scrollController,
        reverse: false,
        physics: const ClampingScrollPhysics(),
        clipBehavior: Clip.hardEdge,
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
        itemCount: timelineEntries.length,
        itemBuilder: (context, index) {
          final dataIndex = timelineEntries.length - 1 - index;
          final entry = timelineEntries[dataIndex];
          final isOldestEntry = dataIndex == timelineEntries.length - 1;
          final needTopPadding = isOldestEntry && !entry.isUserMessage;
          return _ChatTimelineListRow(
            key: ValueKey('chat-message-list-item-${entry.key}'),
            entry: entry,
            latestUserMessageId: latestUserMessageId,
            editingUserMessageId: widget.editingUserMessageId,
            userMessageEditController: widget.userMessageEditController,
            onUserMessageEditCancelled: widget.onUserMessageEditCancelled,
            onUserMessageEditSaved: widget.onUserMessageEditSaved,
            padding: EdgeInsets.only(top: needTopPadding ? 24.0 : 0.0),
            onBeforeTaskExecute: widget.onBeforeTaskExecute,
            onCancelTask: widget.onCancelTask,
            parentScrollController: widget.scrollController,
            onParentScrollHandoff: _handleParentScrollHandoff,
            onRequestAuthorize: widget.onRequestAuthorize,
            onUserMessageLongPressStart: widget.onUserMessageLongPressStart,
            onStreamingTextLayoutChanged: _handleStreamingTextLayoutChanged,
            onToggleAgentRunGroup: _toggleAgentRunGroup,
            expandedAgentRunTaskIds: _expandedAgentRunTaskIds,
            visualProfile: widget.visualProfile,
            appearanceConfig: widget.appearanceConfig,
          );
        },
      );
      content = ClipRect(
        child: Align(
          alignment: Alignment.topCenter,
          child: NotificationListener<ScrollNotification>(
            onNotification: _handleListScrollNotification,
            child: listView,
          ),
        ),
      );
    }

    final paddedContent = AnimatedPadding(
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOutCubic,
      padding: EdgeInsets.only(bottom: reservedBottomInset),
      child: content,
    );

    if (pageBackgroundColor == null) {
      return paddedContent;
    }
    return ColoredBox(color: pageBackgroundColor, child: paddedContent);
  }
}

class _ChatTimelineListRow extends StatelessWidget {
  const _ChatTimelineListRow({
    super.key,
    required this.entry,
    required this.padding,
    required this.onBeforeTaskExecute,
    this.latestUserMessageId,
    this.editingUserMessageId,
    this.userMessageEditController,
    this.onUserMessageEditCancelled,
    this.onUserMessageEditSaved,
    this.onCancelTask,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onUserMessageLongPressStart,
    this.onStreamingTextLayoutChanged,
    required this.onToggleAgentRunGroup,
    required this.expandedAgentRunTaskIds,
    required this.visualProfile,
    required this.appearanceConfig,
  });

  final AgentRunTimelineEntry entry;
  final EdgeInsets padding;
  final Future<void> Function() onBeforeTaskExecute;
  final String? latestUserMessageId;
  final String? editingUserMessageId;
  final TextEditingController? userMessageEditController;
  final VoidCallback? onUserMessageEditCancelled;
  final ValueChanged<ChatMessageModel>? onUserMessageEditSaved;
  final void Function(String taskId)? onCancelTask;
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final void Function(List<String> requiredPermissionIds)? onRequestAuthorize;
  final void Function(ChatMessageModel message, LongPressStartDetails details)?
  onUserMessageLongPressStart;
  final VoidCallback? onStreamingTextLayoutChanged;
  final void Function(String taskId) onToggleAgentRunGroup;
  final Set<String> expandedAgentRunTaskIds;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  @override
  Widget build(BuildContext context) {
    if (entry.message != null) {
      return _buildBubble(entry.message!);
    }
    final group = entry.group!;
    return Padding(
      padding: padding,
      child: AgentRunGroupMessage(
        group: group,
        expanded: expandedAgentRunTaskIds.contains(group.taskId),
        onToggleExpanded: () => onToggleAgentRunGroup(group.taskId),
        onBeforeTaskExecute: onBeforeTaskExecute,
        onCancelTask: onCancelTask,
        parentScrollController: parentScrollController,
        onParentScrollHandoff: onParentScrollHandoff,
        onRequestAuthorize: onRequestAuthorize,
        onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
        visualProfile: visualProfile,
        appearanceConfig: appearanceConfig,
      ),
    );
  }

  Widget _buildBubble(ChatMessageModel currentMessage) {
    final canEditUserMessage =
        currentMessage.user == 1 && currentMessage.id == latestUserMessageId;
    final isEditingUserMessage =
        canEditUserMessage &&
        editingUserMessageId == currentMessage.id &&
        userMessageEditController != null;
    return Padding(
      padding: padding,
      child: MessageBubble(
        message: currentMessage,
        key: ValueKey(
          currentMessage.dbId ?? currentMessage.contentId ?? currentMessage.id,
        ),
        onBeforeTaskExecute: onBeforeTaskExecute,
        onCancelTask: onCancelTask,
        enableThinkingCollapse: true,
        parentScrollController: parentScrollController,
        onParentScrollHandoff: onParentScrollHandoff,
        onRequestAuthorize: onRequestAuthorize,
        onUserMessageLongPressStart: onUserMessageLongPressStart,
        isUserMessageEditing: isEditingUserMessage,
        userMessageEditController: isEditingUserMessage
            ? userMessageEditController
            : null,
        onCancelUserEdit: isEditingUserMessage
            ? onUserMessageEditCancelled
            : null,
        onSaveUserEdit: isEditingUserMessage
            ? () => onUserMessageEditSaved?.call(currentMessage)
            : null,
        onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
        visualProfile: visualProfile,
        appearanceConfig: appearanceConfig,
      ),
    );
  }
}

/// VLM 用户输入提示
class VlmInfoPrompt extends StatelessWidget {
  final String question;
  final TextEditingController controller;
  final bool isSubmitting;
  final VoidCallback onSubmit;
  final VoidCallback onDismiss;

  const VlmInfoPrompt({
    super.key,
    required this.question,
    required this.controller,
    required this.isSubmitting,
    required this.onSubmit,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFE8F2FF),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFF4F83FF)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'Need your confirmation'
                : '需要你的确认',
            style: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: Color(0xFF1D3E7B),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            question,
            style: const TextStyle(fontSize: 13, color: Color(0xFF1D3E7B)),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: controller,
            maxLines: 2,
            decoration: InputDecoration(
              hintText: Localizations.localeOf(context).languageCode == 'en'
                  ? 'Optional: add details. Default sends: Completed action, continue execution'
                  : '可选：补充你的操作说明，默认发送"已完成操作，继续执行"',
              isDense: true,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 8,
              ),
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: isSubmitting ? null : onDismiss,
                  child: Text(
                    Localizations.localeOf(context).languageCode == 'en'
                        ? 'Later'
                        : '稍后再说',
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: ElevatedButton(
                  onPressed: isSubmitting ? null : onSubmit,
                  child: Text(
                    isSubmitting
                        ? (Localizations.localeOf(context).languageCode == 'en'
                              ? 'Sending...'
                              : '发送中...')
                        : (Localizations.localeOf(context).languageCode == 'en'
                              ? 'Continue'
                              : '继续执行'),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// 聊天输入区域包装器
class ChatInputWrapper extends StatelessWidget {
  final GlobalKey<ChatInputAreaState> inputAreaKey;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool isProcessing;
  final Future<void> Function({String? text}) onSendMessage;
  final VoidCallback onCancelTask;
  final void Function(bool) onPopupVisibilityChanged;
  final bool? openClawEnabled;
  final ValueChanged<bool>? onToggleOpenClaw;
  final VoidCallback? onLongPressOpenClaw;
  final bool useLargeComposerStyle;
  final bool useAttachmentPickerForPlus;
  final Future<void> Function()? onPickAttachment;
  final List<ChatInputAttachment> attachments;
  final ValueChanged<String>? onRemoveAttachment;
  final VoidCallback? onTriggerSlashCommand;
  final Widget? topBanner;
  final String? selectedModelOverrideId;
  final VoidCallback? onClearSelectedModelOverride;
  final double? contextUsageRatio;
  final String? contextUsageTooltipMessage;
  final VoidCallback? onLongPressContextUsageRing;
  final ValueChanged<double>? onInputHeightChanged;
  final CodexPermissionMode? codexPermissionMode;
  final ValueChanged<CodexPermissionMode>? onCodexPermissionModeChanged;
  final bool translucent;

  const ChatInputWrapper({
    super.key,
    required this.inputAreaKey,
    required this.controller,
    required this.focusNode,
    required this.isProcessing,
    required this.onSendMessage,
    required this.onCancelTask,
    required this.onPopupVisibilityChanged,
    this.openClawEnabled,
    this.onToggleOpenClaw,
    this.onLongPressOpenClaw,
    this.useLargeComposerStyle = false,
    this.useAttachmentPickerForPlus = false,
    this.onPickAttachment,
    this.attachments = const [],
    this.onRemoveAttachment,
    this.onTriggerSlashCommand,
    this.topBanner,
    this.selectedModelOverrideId,
    this.onClearSelectedModelOverride,
    this.contextUsageRatio,
    this.contextUsageTooltipMessage,
    this.onLongPressContextUsageRing,
    this.onInputHeightChanged,
    this.codexPermissionMode,
    this.onCodexPermissionModeChanged,
    this.translucent = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (topBanner != null) ...[topBanner!, const SizedBox(height: 8)],
          ChatInputArea(
            key: inputAreaKey,
            controller: controller,
            focusNode: focusNode,
            isProcessing: isProcessing,
            onSendMessage: onSendMessage,
            onCancelTask: onCancelTask,
            onPopupVisibilityChanged: onPopupVisibilityChanged,
            openClawEnabled: openClawEnabled,
            onToggleOpenClaw: onToggleOpenClaw,
            onLongPressOpenClaw: onLongPressOpenClaw,
            useFrostedGlass: translucent,
            useLargeComposerStyle: useLargeComposerStyle,
            useAttachmentPickerForPlus: useAttachmentPickerForPlus,
            onPickAttachment: onPickAttachment,
            attachments: attachments,
            onRemoveAttachment: onRemoveAttachment,
            onTriggerSlashCommand: onTriggerSlashCommand,
            selectedModelOverrideId: selectedModelOverrideId,
            onClearSelectedModelOverride: onClearSelectedModelOverride,
            contextUsageRatio: contextUsageRatio,
            contextUsageTooltipMessage: contextUsageTooltipMessage,
            onLongPressContextUsageRing: onLongPressContextUsageRing,
            codexPermissionMode: codexPermissionMode,
            onCodexPermissionModeChanged: onCodexPermissionModeChanged,
            onInputHeightChanged: onInputHeightChanged,
          ),
        ],
      ),
    );
  }
}
