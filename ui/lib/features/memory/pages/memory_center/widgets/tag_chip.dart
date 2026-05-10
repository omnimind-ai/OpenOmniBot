import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';

class TagChip extends StatelessWidget {
  final String title;
  final IconData? iconPath;
  final String? svgPath;
  final ImageProvider? appIconProvider;
  final bool selected;
  final Color? backgroundColor;
  final bool showIcon;

  const TagChip({
    Key? key,
    required this.title,
    this.iconPath,
    this.svgPath,
    this.appIconProvider,
    this.selected = false,
    this.backgroundColor,
    this.showIcon = true,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      height: 24,
      decoration: BoxDecoration(
        color: selected
            ? palette.segmentThumb
            : backgroundColor ?? palette.surfacePrimary,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          if (showIcon) ...[
            if (appIconProvider != null) ...[
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: Image(
                  image: appIconProvider!,
                  width: 13,
                  height: 13,
                  errorBuilder: (context, error, stackTrace) {
                    return Icon(
                      Icons.apps,
                      size: 13,
                      color: palette.textPrimary,
                    );
                  },
                ),
              ),
            ] else if (svgPath != null) ...[
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: SvgPicture.asset(
                  svgPath!,
                  width: 13,
                  height: 13,
                  colorFilter: ColorFilter.mode(
                    selected ? palette.accentPrimary : palette.textPrimary,
                    BlendMode.srcIn,
                  ),
                  placeholderBuilder: (context) =>
                      Icon(Icons.image, size: 13, color: palette.textPrimary),
                ),
              ),
            ] else if (iconPath != null) ...[
              Icon(
                iconPath,
                size: 13,
                color: selected ? palette.accentPrimary : palette.textPrimary,
              ),
            ] else ...[
              Icon(Icons.label_outline, size: 13, color: palette.textPrimary),
            ],
            SizedBox(width: 5),
          ],
          Text(
            title,
            style: TextStyle(
              fontSize: AppTextStyles.fontSizeSmall,
              color: selected ? palette.accentPrimary : palette.textPrimary,
              fontWeight: selected
                  ? AppTextStyles.fontWeightMedium
                  : AppTextStyles.fontWeightRegular,
              height: 0.92,
              letterSpacing: AppTextStyles.letterSpacingWide,
            ),
          ),
        ],
      ),
    );
  }
}
