import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class OpenWithOmnibotSettingPage extends StatefulWidget {
  const OpenWithOmnibotSettingPage({super.key});

  @override
  State<OpenWithOmnibotSettingPage> createState() =>
      _OpenWithOmnibotSettingPageState();
}

class _OpenWithOmnibotSettingPageState
    extends State<OpenWithOmnibotSettingPage> {
  static const String _sharedOpenModeDefault = 'default';
  static const String _sharedOpenModeWorkspace = 'workspace';
  static const String _targetImage = 'image';
  static const String _targetFile = 'file';

  String _sharedOpenImageMode = _sharedOpenModeDefault;
  String _sharedOpenFileMode = _sharedOpenModeDefault;
  bool _sharedOpenModesLoaded = false;

  @override
  void initState() {
    super.initState();
    _loadSharedOpenMode();
  }

  Future<void> _loadSharedOpenMode() async {
    final modes = await AppStateService.getSharedOpenModes();
    if (!mounted) return;
    setState(() {
      _sharedOpenImageMode = _normalizeSharedOpenMode(modes['imageMode']);
      _sharedOpenFileMode = _normalizeSharedOpenMode(modes['fileMode']);
      _sharedOpenModesLoaded = true;
    });
  }

  Future<void> _onSharedOpenModeChanged({
    required String target,
    required String? value,
  }) async {
    final nextMode = _normalizeSharedOpenMode(value);
    final isImageTarget = target == _targetImage;
    final currentMode = isImageTarget
        ? _sharedOpenImageMode
        : _sharedOpenFileMode;
    if (nextMode == currentMode) return;
    final previousMode = currentMode;
    setState(() {
      if (isImageTarget) {
        _sharedOpenImageMode = nextMode;
      } else {
        _sharedOpenFileMode = nextMode;
      }
    });
    final saved = await AppStateService.setSharedOpenMode(
      nextMode,
      target: target,
    );
    if (!mounted) return;
    final normalizedSaved = _normalizeSharedOpenMode(saved);
    setState(() {
      if (isImageTarget) {
        _sharedOpenImageMode = normalizedSaved;
      } else {
        _sharedOpenFileMode = normalizedSaved;
      }
    });
    if (normalizedSaved != nextMode) {
      setState(() {
        if (isImageTarget) {
          _sharedOpenImageMode = previousMode;
        } else {
          _sharedOpenFileMode = previousMode;
        }
      });
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
    }
  }

  String _normalizeSharedOpenMode(String? value) {
    return switch (value?.trim()) {
      _sharedOpenModeWorkspace => _sharedOpenModeWorkspace,
      _ => _sharedOpenModeDefault,
    };
  }

  String _defaultModeLabel(String target) {
    return target == _targetImage
        ? context.trLegacy('附加到对话输入框')
        : context.trLegacy('生成局域网文件链接');
  }

  String _modeSubtitle({required String target, required String mode}) {
    if (mode == _sharedOpenModeWorkspace) {
      return context.trLegacy('添加到 workspace，附加到对话，并在提示词中发送文件路径');
    }
    return target == _targetImage
        ? context.trLegacy('照片等文件会直接附加到对话输入框')
        : context.trLegacy('非照片文件会启动文件服务器并生成局域网链接');
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final sections = [
      _SettingSection(
        label: context.trLegacy('使用小万打开'),
        items: [
          _SettingItem(
            icon: Icons.image_outlined,
            title: context.trLegacy('图片'),
            subtitle: _modeSubtitle(
              target: _targetImage,
              mode: _sharedOpenImageMode,
            ),
            trailing: _buildSharedOpenModeDropdown(
              target: _targetImage,
              value: _sharedOpenImageMode,
              onChanged: (value) =>
                  _onSharedOpenModeChanged(target: _targetImage, value: value),
            ),
          ),
          _SettingItem(
            icon: Icons.insert_drive_file_outlined,
            title: context.trLegacy('文件'),
            subtitle: _modeSubtitle(
              target: _targetFile,
              mode: _sharedOpenFileMode,
            ),
            trailing: _buildSharedOpenModeDropdown(
              target: _targetFile,
              value: _sharedOpenFileMode,
              onChanged: (value) =>
                  _onSharedOpenModeChanged(target: _targetFile, value: value),
            ),
          ),
        ],
      ),
    ];

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.trLegacy('使用小万打开'), primary: true),
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
    );
  }

  Widget _buildSharedOpenModeDropdown({
    required String target,
    required String value,
    required ValueChanged<String?> onChanged,
  }) {
    final palette = context.omniPalette;
    if (!_sharedOpenModesLoaded) {
      return Padding(
        padding: const EdgeInsets.only(left: 12),
        child: SizedBox(
          width: 18,
          height: 18,
          child: CircularProgressIndicator(
            strokeWidth: 2,
            color: palette.accentPrimary,
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(left: 12),
      child: SizedBox(
        width: 132,
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: value,
            isDense: true,
            isExpanded: true,
            borderRadius: BorderRadius.circular(10),
            dropdownColor: palette.surfacePrimary,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
            icon: Icon(
              Icons.keyboard_arrow_down_rounded,
              size: 18,
              color: palette.textTertiary,
            ),
            items: [
              DropdownMenuItem(
                value: _sharedOpenModeDefault,
                child: _buildDropdownText(_defaultModeLabel(target)),
              ),
              DropdownMenuItem(
                value: _sharedOpenModeWorkspace,
                child: _buildDropdownText(
                  context.trLegacy('存入 workspace 并发送路径'),
                ),
              ),
            ],
            onChanged: onChanged,
          ),
        ),
      ),
    );
  }

  Widget _buildDropdownText(String text) {
    return Text(text, maxLines: 1, overflow: TextOverflow.ellipsis);
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
                  letterSpacing: 0,
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
    return Material(
      color: Colors.transparent,
      child: InkWell(
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
              if (item.trailing != null) item.trailing!,
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLeadingIcon(_SettingItem item) {
    final palette = context.omniPalette;
    return SizedBox(
      width: 18,
      height: 18,
      child: item.icon != null
          ? Icon(item.icon, size: 18, color: palette.textPrimary)
          : const SizedBox.shrink(),
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
  final String title;
  final String? subtitle;
  final Widget? trailing;

  const _SettingItem({
    this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
  });
}
