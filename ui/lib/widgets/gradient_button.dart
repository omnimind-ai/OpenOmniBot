import 'package:flutter/material.dart';

/// 可定制的渐变按钮组件
class GradientButton extends StatelessWidget {
  /// 按钮文字
  final String text;

  /// 按钮宽度
  final double width;

  /// 按钮高度
  final double height;

  /// 点击事件回调
  final VoidCallback? onTap;

  /// 文字样式，如果不提供则使用默认样式
  final TextStyle? textStyle;

  /// 渐变色列表
  final List<Color> gradientColors;

  /// 渐变开始位置
  final Alignment gradientBegin;

  /// 渐变结束位置
  final Alignment gradientEnd;

  /// 圆角半径
  final double borderRadius;

  /// 是否启用（禁用时会显示灰色）
  final bool enabled;

  /// 文字后的图标（可选）
  final Widget? trailingIcon;

  /// 文字和图标之间的间距
  final double iconSpacing;

  const GradientButton({
    super.key,
    required this.text,
    required this.onTap,
    this.width = 166,
    this.height = 44,
    this.textStyle,
    this.gradientColors = const [Color(0xFF1930D9), Color(0xFF2DA5F0)],
    this.gradientBegin = const Alignment(0.14, -1.09),
    this.gradientEnd = const Alignment(1.10, 1.26),
    this.borderRadius = 8,
    this.enabled = true,
    this.trailingIcon,
    this.iconSpacing = 4,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent, // 保持透明，不遮盖渐变
      child: Ink(
        decoration: ShapeDecoration(
          gradient: enabled
              ? LinearGradient(
                  begin: gradientBegin,
                  end: gradientEnd,
                  colors: gradientColors,
                )
              : LinearGradient(
                  begin: gradientBegin,
                  end: gradientEnd,
                  colors: [Colors.grey.shade400, Colors.grey.shade300],
                ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(borderRadius)),
          ),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(borderRadius), // 波纹与圆角一致
          onTap: enabled ? onTap : null,
          child: SizedBox(
            width: width,
            height: height,
            child: Row(
              mainAxisSize: MainAxisSize.max,
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Flexible(
                  child: Text(
                    text,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                    style:
                        textStyle ??
                        const TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontFamily: 'PingFang SC',
                          fontWeight: FontWeight.w500,
                          height: 1.5,
                          letterSpacing: 0.5,
                        ),
                  ),
                ),
                if (trailingIcon != null) ...[
                  SizedBox(width: iconSpacing),
                  trailingIcon!,
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
