import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/settings_section_title.dart';

class ThemeModeSettingCard extends ConsumerWidget {
  const ThemeModeSettingCard({
    super.key,
    this.title,
    this.subtitle,
  });

  final String? title;
  final String? subtitle;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(appThemeModeProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: title ?? context.l10n.themeModeTitle,
          subtitle: subtitle ?? context.l10n.themeModeSubtitle,
          bottomPadding: 10,
        ),
        OmniSegmentedSlider<AppThemeMode>(
          key: const ValueKey('theme-mode-slider'),
          value: mode,
          keyPrefix: 'theme-mode-option',
          options: [
            OmniSegmentedOption<AppThemeMode>(
              value: AppThemeMode.system,
              label: context.l10n.themeModeSystem,
              icon: Icons.brightness_auto_rounded,
              id: 'system',
            ),
            OmniSegmentedOption<AppThemeMode>(
              value: AppThemeMode.light,
              label: context.l10n.themeModeLight,
              icon: Icons.light_mode_rounded,
              id: 'light',
            ),
            OmniSegmentedOption<AppThemeMode>(
              value: AppThemeMode.dark,
              label: context.l10n.themeModeDark,
              icon: Icons.dark_mode_rounded,
              id: 'dark',
            ),
          ],
          onChanged: (nextMode) {
            ref.read(appThemeModeProvider.notifier).setThemeMode(nextMode);
          },
        ),
      ],
    );
  }
}
