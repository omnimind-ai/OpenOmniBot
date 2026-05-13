import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';

class SettingsSectionTitle extends StatelessWidget {
  const SettingsSectionTitle({
    super.key,
    required this.label,
    this.subtitle,
    this.topPadding = 0,
    this.bottomPadding = 12,
  });

  final String label;
  final String? subtitle;
  final double topPadding;
  final double bottomPadding;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: EdgeInsets.only(top: topPadding, bottom: bottomPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                context.trText(label),
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
          if (subtitle != null) ...[
            const SizedBox(height: 6),
            Text(
              context.trText(subtitle!),
              style: TextStyle(
                fontSize: 12,
                height: 1.5,
                color: palette.textSecondary,
                fontWeight: FontWeight.w400,
              ),
            ),
          ],
        ],
      ),
    );
  }
}
