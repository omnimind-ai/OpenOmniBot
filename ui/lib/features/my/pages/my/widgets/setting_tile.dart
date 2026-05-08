import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:flutter_svg/svg.dart';
// 单行项
class SettingTile extends StatelessWidget {
  final String title;
  final VoidCallback? onTap;
  final Widget? trailing;
  final double? height;
  final bool showChevron;

  const SettingTile({
    super.key,
    required this.title,
    this.onTap,
    this.trailing,
    this.height,
    this.showChevron = true,
  });

  @override
  Widget build(BuildContext context) {
    final hasTap = onTap != null;
    return Semantics(
      button: hasTap,
      enabled: hasTap || trailing != null,
      label: context.trLegacy(title),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(0),
        child: Container(
          height: height ?? 48,
          padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 10),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  context.trLegacy(title),
                  style: TextStyle(
                    fontSize: AppTextStyles.fontSizeMain,
                    fontWeight: FontWeight.w300,
                    letterSpacing: 0.44,
                    color: AppColors.text90,
                  ),
                ),
              ),
              if (trailing != null) trailing!,
              if (showChevron)      //无尾部组件，有箭头
                SizedBox(
                  width: 16,
                  height: 16,
                  child: SvgPicture.asset(
                    'assets/my/chevron_right.svg',
                    width: 16,
                    height: 16,
                    colorFilter: const ColorFilter.mode(
                      AppColors.text90, // icon_nav_secondary
                      BlendMode.srcIn,
                    ),
                  ),
                ),
              if (!hasTap && trailing == null)
                const SizedBox.shrink(),
            ],
          ),
        ),
      ),
    );
  }
}
