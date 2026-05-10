import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/app_colors.dart';

/// AI 生成内容标识组件
/// 用于显示 "内容由AI生成" 的提示标识，包含图标和文案
class AiGeneratedBadge extends StatelessWidget {
  /// 自定义文案，默认为 "内容由Ai生成"
  final String? text;
  
  /// 图标大小，默认 10
  final double iconSize;
  
  /// 文字大小，默认 12
  final double fontSize;
  
  /// 图标和文字颜色，默认使用 AppColors.text50
  final Color? color;

  const AiGeneratedBadge({
    super.key,
    this.text,
    this.iconSize = 10,
    this.fontSize = 12,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    final effectiveColor = color ?? AppColors.text50;
    
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SvgPicture.asset(
          'assets/execution_history/model_icon.svg',
          width: iconSize,
          height: iconSize,
          colorFilter: ColorFilter.mode(
            effectiveColor,
            BlendMode.srcIn,
          ),
        ),
        const SizedBox(width: 4),
        Text(
          text ?? (LegacyTextLocalizer.isEnglish ? 'AI generated content' : '内容由Ai生成'),
          style: TextStyle(
            color: effectiveColor,
            fontSize: fontSize,
            fontFamily: 'PingFang SC',
            fontWeight: FontWeight.w400,
            height: 1.50,
          ),
        ),
      ],
    );
  }
}
