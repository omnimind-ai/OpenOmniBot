import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:ui/features/home/widgets/home_quick_prompt_icon.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class HomeSettingPage extends StatefulWidget {
  const HomeSettingPage({super.key});

  @override
  State<HomeSettingPage> createState() => _HomeSettingPageState();
}

class _HomeSettingPageState extends State<HomeSettingPage> {
  @override
  void initState() {
    super.initState();
    HomeGreetingSettingsService.notifier.addListener(_handleSettingsChanged);
    unawaited(HomeGreetingSettingsService.load());
  }

  @override
  void dispose() {
    HomeGreetingSettingsService.notifier.removeListener(_handleSettingsChanged);
    super.dispose();
  }

  void _handleSettingsChanged() {
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _setGreetingEnabled(bool value) async {
    final saved = await HomeGreetingSettingsService.setGreetingEnabled(value);
    if (!saved && mounted) {
      showToast(context.trLegacy('设置失败'), type: ToastType.error);
    }
  }

  Future<void> _deletePrompt(HomeQuickPrompt prompt) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(context.trLegacy('删除快捷指令')),
          content: Text(context.trLegacy('删除后该快捷指令将不再显示在首页问候语下方。')),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(context.trLegacy('取消')),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(context.trLegacy('删除')),
            ),
          ],
        );
      },
    );
    if (confirmed != true) {
      return;
    }
    final saved = await HomeGreetingSettingsService.deleteQuickPrompt(
      prompt.id,
    );
    if (!saved && mounted) {
      showToast(context.trLegacy('删除失败'), type: ToastType.error);
    }
  }

  Future<void> _resetPrompts() async {
    final saved = await HomeGreetingSettingsService.resetQuickPrompts();
    if (!saved && mounted) {
      showToast(context.trLegacy('设置失败'), type: ToastType.error);
    }
  }

  Future<void> _togglePinnedPrompt(
    HomeQuickPrompt prompt,
    HomeGreetingSettings settings,
  ) async {
    final isPinned = settings.pinnedQuickPromptIds.contains(prompt.id);
    if (!isPinned && settings.pinnedQuickPromptIds.length >= 2) {
      showToast(context.trLegacy('最多固定两个快捷指令'));
      return;
    }
    final saved = await HomeGreetingSettingsService.togglePinnedQuickPrompt(
      prompt.id,
    );
    if (!saved && mounted) {
      showToast(context.trLegacy('设置失败'), type: ToastType.error);
    }
  }

  Future<void> _showPromptEditor({HomeQuickPrompt? prompt}) async {
    final titleController = TextEditingController(
      text: prompt == null ? '' : prompt.title,
    );
    final promptController = TextEditingController(
      text: prompt == null ? '' : prompt.prompt,
    );
    final result = await showModalBottomSheet<_PromptEditorResult>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return _PromptEditorSheet(
          titleController: titleController,
          promptController: promptController,
          editing: prompt != null,
        );
      },
    );
    titleController.dispose();
    promptController.dispose();
    if (result == null) {
      return;
    }
    final saved = prompt == null
        ? await HomeGreetingSettingsService.addQuickPrompt(
            title: result.title,
            prompt: result.prompt,
          )
        : await HomeGreetingSettingsService.updateQuickPrompt(
            prompt.copyWith(title: result.title, prompt: result.prompt),
          );
    if (!saved && mounted) {
      showToast(context.trLegacy('保存失败'), type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final settings = HomeGreetingSettingsService.notifier.value;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.trLegacy('首页设置'), primary: true),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
          children: [
            _buildSectionTitle(context.trLegacy('首页问候')),
            _SettingSurface(
              child: _buildGreetingSwitch(settings.greetingEnabled),
            ),
            const SizedBox(height: 24),
            _buildPromptHeader(),
            const SizedBox(height: 8),
            _SettingSurface(
              child: settings.quickPrompts.isEmpty
                  ? _buildEmptyPrompts()
                  : Column(
                      children: List.generate(settings.quickPrompts.length, (
                        index,
                      ) {
                        final prompt = settings.quickPrompts[index];
                        final isLast =
                            index == settings.quickPrompts.length - 1;
                        return Column(
                          children: [
                            _buildPromptTile(prompt, settings),
                            if (!isLast)
                              Divider(
                                height: 1,
                                thickness: 1,
                                color: palette.borderSubtle.withValues(
                                  alpha: context.isDarkTheme ? 0.5 : 0.78,
                                ),
                              ),
                          ],
                        );
                      }),
                    ),
            ),
            const SizedBox(height: 14),
            _buildAddButton(),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String label) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
      child: Row(
        children: [
          Text(
            label,
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
    );
  }

  Widget _buildGreetingSwitch(bool value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 14, 2, 14),
      child: Row(
        children: [
          Icon(
            Icons.waving_hand_outlined,
            size: 18,
            color: palette.textPrimary,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  context.trLegacy('显示问候语'),
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  context.trLegacy('关闭后，聊天首页不再显示问候语和快捷指令'),
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 11,
                    fontWeight: FontWeight.w400,
                    height: 1.55,
                  ),
                ),
              ],
            ),
          ),
          _buildSwitchTrailing(value: value, onToggle: _setGreetingEnabled),
        ],
      ),
    );
  }

  Widget _buildPromptHeader() {
    return Row(
      children: [
        Expanded(child: _buildSectionTitle(context.trLegacy('快捷指令'))),
        TextButton(
          onPressed: _resetPrompts,
          child: Text(context.trLegacy('恢复默认')),
        ),
      ],
    );
  }

  Widget _buildPromptTile(
    HomeQuickPrompt prompt,
    HomeGreetingSettings settings,
  ) {
    final palette = context.omniPalette;
    final pinnedIndex = settings.pinnedQuickPromptIds.indexOf(prompt.id);
    final isPinned = pinnedIndex >= 0;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 13, 0, 13),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: palette.accentPrimary.withValues(
                alpha: context.isDarkTheme ? 0.16 : 0.1,
              ),
              shape: BoxShape.circle,
            ),
            child: Icon(
              homeQuickPromptIcon(prompt.iconKey),
              size: 17,
              color: palette.accentPrimary,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        prompt.resolveTitle(context),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                          color: palette.textPrimary,
                          height: 1.35,
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    _PromptTypeBadge(builtIn: prompt.builtIn),
                    if (isPinned) ...[
                      const SizedBox(width: 6),
                      _PinnedBadge(index: pinnedIndex + 1),
                    ],
                  ],
                ),
                const SizedBox(height: 3),
                Text(
                  prompt.resolvePrompt(context),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 11,
                    height: 1.45,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          IconButton(
            tooltip: context.trLegacy(isPinned ? '取消固定' : '固定到首页'),
            onPressed: () => _togglePinnedPrompt(prompt, settings),
            icon: Icon(
              isPinned ? Icons.push_pin_rounded : Icons.push_pin_outlined,
              size: 18,
              color: isPinned ? palette.accentPrimary : palette.textTertiary,
            ),
          ),
          if (!prompt.builtIn)
            IconButton(
              tooltip: context.trLegacy('编辑'),
              onPressed: () => _showPromptEditor(prompt: prompt),
              icon: Icon(
                Icons.edit_outlined,
                size: 18,
                color: palette.textTertiary,
              ),
            ),
          IconButton(
            tooltip: context.trLegacy('删除'),
            onPressed: () => _deletePrompt(prompt),
            icon: Icon(
              Icons.delete_outline_rounded,
              size: 18,
              color: palette.textTertiary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyPrompts() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: Text(
          context.trLegacy('暂无快捷指令'),
          style: TextStyle(color: palette.textSecondary, fontSize: 13),
        ),
      ),
    );
  }

  Widget _buildAddButton() {
    final palette = context.omniPalette;
    return SizedBox(
      height: 42,
      child: OutlinedButton.icon(
        onPressed: () => _showPromptEditor(),
        icon: const Icon(Icons.add_rounded, size: 18),
        label: Text(context.trLegacy('新增快捷指令')),
        style: OutlinedButton.styleFrom(
          foregroundColor: palette.accentPrimary,
          side: BorderSide(
            color: palette.accentPrimary.withValues(alpha: 0.35),
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(999),
          ),
        ),
      ),
    );
  }

  Widget _buildSwitchTrailing({
    required bool value,
    required ValueChanged<bool> onToggle,
  }) {
    final palette = context.omniPalette;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => onToggle(!value),
      child: Padding(
        padding: const EdgeInsets.only(left: 12),
        child: AbsorbPointer(
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
    );
  }
}

class _SettingSurface extends StatelessWidget {
  final Widget child;

  const _SettingSurface({required this.child});

  @override
  Widget build(BuildContext context) {
    return child;
  }
}

class _PromptTypeBadge extends StatelessWidget {
  final bool builtIn;

  const _PromptTypeBadge({required this.builtIn});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(
          alpha: context.isDarkTheme ? 0.8 : 1,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        context.trLegacy(builtIn ? '内置' : '用户'),
        style: TextStyle(
          color: palette.textTertiary,
          fontSize: 10,
          height: 1,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }
}

class _PinnedBadge extends StatelessWidget {
  final int index;

  const _PinnedBadge({required this.index});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withValues(
          alpha: context.isDarkTheme ? 0.18 : 0.1,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '${context.trLegacy('固定')} $index',
        style: TextStyle(
          color: palette.accentPrimary,
          fontSize: 10,
          height: 1,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _PromptEditorSheet extends StatelessWidget {
  final TextEditingController titleController;
  final TextEditingController promptController;
  final bool editing;

  const _PromptEditorSheet({
    required this.titleController,
    required this.promptController,
    required this.editing,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottomInset),
      child: Material(
        color: palette.pageBackground,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                context.trLegacy(editing ? '编辑快捷指令' : '新增快捷指令'),
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: titleController,
                maxLength: 12,
                decoration: InputDecoration(
                  labelText: context.trLegacy('指令名称'),
                  isDense: true,
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: promptController,
                minLines: 3,
                maxLines: 5,
                maxLength: 160,
                decoration: InputDecoration(
                  labelText: context.trLegacy('填充文本'),
                  alignLabelWithHint: true,
                ),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: Text(context.trLegacy('取消')),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: () {
                        final title = titleController.text.trim();
                        final prompt = promptController.text.trim();
                        if (title.isEmpty || prompt.isEmpty) {
                          showToast(
                            context.trLegacy('请填写完整信息'),
                            type: ToastType.error,
                          );
                          return;
                        }
                        Navigator.of(context).pop(
                          _PromptEditorResult(title: title, prompt: prompt),
                        );
                      },
                      child: Text(context.trLegacy('保存')),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PromptEditorResult {
  final String title;
  final String prompt;

  const _PromptEditorResult({required this.title, required this.prompt});
}
