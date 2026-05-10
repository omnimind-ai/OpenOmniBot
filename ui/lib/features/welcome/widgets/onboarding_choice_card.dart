import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/theme_context.dart';

/// A flat, settings-style tappable item for the onboarding choice page.
///
/// Uses accent-colored SVG icon, title/subtitle text, and InkWell ripple
/// feedback — matching the app's flat design language from the settings page.
class OnboardingChoiceCard extends StatelessWidget {
  final String svgIcon;
  final String title;
  final String subtitle;
  final bool completed;
  final VoidCallback onTap;

  const OnboardingChoiceCard({
    super.key,
    required this.svgIcon,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.completed = false,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 16),
          child: Row(
            children: [
              SvgPicture.string(
                svgIcon,
                width: 20,
                height: 20,
                colorFilter: ColorFilter.mode(
                  palette.accentPrimary,
                  BlendMode.srcIn,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                        height: 1.4,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w400,
                        color: palette.textSecondary,
                        height: 1.5,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              if (completed)
                Icon(
                  Icons.check_circle_rounded,
                  size: 20,
                  color: palette.accentPrimary,
                )
              else
                Icon(
                  Icons.chevron_right_rounded,
                  size: 20,
                  color: palette.textTertiary,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
