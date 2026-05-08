import 'package:flutter/material.dart';
import 'package:flutter_svg/svg.dart';
import 'package:simple_gradient_text/simple_gradient_text.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';

/// 构建Shimmer加载占位符
Widget _buildShimmerPlaceholder({
  required double width,
  required double height,
  required Animation<double> animation,
  BorderRadius? borderRadius,
}) {
  return AnimatedBuilder(
    animation: animation,
    builder: (context, child) {
      final palette = context.omniPalette;
      final isDark = context.isDarkTheme;
      return Container(
        width: width,
        height: height,
        decoration: BoxDecoration(
          borderRadius: borderRadius ?? BorderRadius.circular(4),
          gradient: LinearGradient(
            begin: Alignment.centerLeft,
            end: Alignment.centerRight,
            colors: isDark
                ? [
                    palette.surfaceSecondary,
                    Color.lerp(
                      palette.surfaceSecondary,
                      palette.accentPrimary,
                      0.18,
                    )!,
                    palette.surfaceSecondary,
                  ]
                : [
                    Color(0xFF2DA5F0).withValues(alpha: 0.1),
                    Color(0xFF1930D9).withValues(alpha: 0.25),
                    Color(0xFF2DA5F0).withValues(alpha: 0.1),
                  ],
            stops: [0.0, animation.value, 1.0],
          ),
        ),
      );
    },
  );
}

/// 自定义 Clipper,根据 SVG path 裁剪
/// 保持顶部梯形标签区域固定尺寸,底部矩形区域自适应
class MemorySummaryClipper extends CustomClipper<Path> {
  @override
  Path getClip(Size size) {
    // 固定的梯形标签尺寸(与设计稿一致)
    const double tabStartX = 138.5; // 梯形起点
    const double tabHeight = 21.3359; // 标签高度(固定)
    const double tabTopWidth = 30.257; // 梯形顶部宽度 (168.743 - 138.5)

    // 统一的圆角半径
    const double cornerRadius = 7.627;

    final path = Path();

    // 从梯形起点开始
    path.moveTo(tabStartX, 0);

    // 梯形左侧斜边 - 使用贝塞尔曲线
    path.cubicTo(152, 0, 154, tabHeight, tabStartX + tabTopWidth, tabHeight);

    // 梯形底部水平线到右侧
    path.lineTo(size.width - cornerRadius, tabHeight);

    // 右上角圆角过渡
    path.cubicTo(
      size.width - cornerRadius,
      tabHeight,
      size.width,
      tabHeight + 2.156,
      size.width,
      tabHeight + cornerRadius,
    );

    // 右侧垂直线(自适应高度)
    path.lineTo(size.width, size.height - cornerRadius);

    // 右下角圆角
    path.cubicTo(
      size.width,
      size.height - 2.156,
      size.width - cornerRadius,
      size.height,
      size.width - cornerRadius,
      size.height,
    );

    // 底部水平线
    path.lineTo(cornerRadius, size.height);

    // 左下角圆角
    path.cubicTo(
      3.41464,
      size.height,
      0,
      size.height - 2.156,
      0,
      size.height - 4.816,
    );

    // 左侧垂直线
    path.lineTo(0, 4.81553);

    // 左上角圆角
    path.cubicTo(0, 2.156, 3.41464, 0, cornerRadius, 0);

    // 连接到梯形起点
    path.lineTo(tabStartX, 0);
    path.close();

    return path;
  }

  @override
  bool shouldReclip(CustomClipper<Path> oldClipper) => false;
}

class MemorySection extends StatefulWidget {
  final MemorySummary? memorySummary;
  final bool isSummaryLoading;
  final VoidCallback? onTap;
  final AnimationController? animationController;

  const MemorySection({
    Key? key,
    this.memorySummary,
    this.isSummaryLoading = false,
    this.onTap,
    this.animationController,
  }) : super(key: key);

  @override
  State<MemorySection> createState() => _MemorySectionState();
}

class _MemorySectionState extends State<MemorySection> {
  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    // bool isEmpty = widget.memorySummary == null;
    final label = Localizations.localeOf(context).languageCode == 'en'
        ? 'Open memory trajectory'
        : '打开记忆轨迹';
    return Semantics(
      button: widget.onTap != null,
      enabled: widget.onTap != null,
      label: label,
      child: GestureDetector(
        onTap: widget.onTap,
        child: Container(
          width: double.infinity,
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(10)),
          clipBehavior: Clip.antiAlias,
          child: Stack(
            children: [
            // 渐变背景层 - 作为空状态的背景
            // if (isEmpty)
            //   Positioned.fill(
            //     child: DecoratedBox(
            //       decoration: const BoxDecoration(
            //         gradient: RadialGradient(
            //           center: Alignment(-1.19, 1.19),
            //           radius: 4.2,
            //           colors: [
            //             Color(0x26EEEEEE),
            //             Color(0x2600AEFF),
            //           ],
            //           stops: [0.0, 1.0],
            //         ),
            //       ),
            //     ),
            //   ),
            // if(!isEmpty)...[
            // 使用 ClipPath 和渐变背景层
            // Positioned.fill(
            //   child: ClipPath(
            //     clipper: MemorySummaryClipper(),
            //     child: Container(
            //       decoration: const BoxDecoration(
            //         gradient: LinearGradient(
            //           begin: Alignment(-0.98, -0.06),
            //           end: Alignment(1.02, 1.16),
            //           colors: [
            //             Color(0x0D6075FE), // #6075FE with 5% opacity
            //             Color(0x0D00AEFF), // #00AEFF with 5% opacity
            //             Color(0x0DC0F4FD), // #C0F4FD with 5% opacity
            //           ],
            //           stops: [0.0, 0.406189, 1.0],
            //         ),
            //       ),
            //     ),
            //   ),
            // ),
            // 正文内容
            Padding(
              padding: EdgeInsets.zero,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildTitle(context),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Expanded(
                        child: Text.rich(
                          TextSpan(
                            children: [
                              TextSpan(
                                text: widget.memorySummary!.title + '\n',
                                style: TextStyle(
                                  color: context.isDarkTheme
                                      ? palette.textSecondary
                                      : AppColors.text70,
                                  fontSize: 12,
                                  fontFamily: 'PingFang SC',
                                  fontWeight: FontWeight.w600,
                                  height: 1.50,
                                  letterSpacing: 0.39,
                                ),
                              ),
                              TextSpan(
                                text: widget.memorySummary!.tips,
                                style: TextStyle(
                                  color: context.isDarkTheme
                                      ? palette.textSecondary
                                      : AppColors.text70,
                                  fontSize: 12,
                                  fontFamily: 'PingFang SC',
                                  fontWeight: FontWeight.w400,
                                  height: 1.50,
                                  letterSpacing: 0.39,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      if (widget.onTap != null)
                        SizedBox(
                          width: 16,
                          height: 16,
                          child: SvgPicture.asset(
                            'assets/my/chevron_right.svg',
                            width: 16,
                            height: 16,
                            colorFilter: ColorFilter.mode(
                              context.isDarkTheme
                                  ? palette.textTertiary
                                  : AppColors.text90,
                              BlendMode.srcIn,
                            ),
                          ),
                        ),
                    ],
                  ),
                  widget.isSummaryLoading
                      ? Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(height: 2),
                            _buildShimmerPlaceholder(
                              width: 180,
                              height: 14,
                              animation:
                                  widget.animationController ??
                                  AlwaysStoppedAnimation(0.5),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            SizedBox(height: 2),
                          ],
                        )
                      : Text(
                          widget.memorySummary?.sum ?? '',
                          style: TextStyle(
                            color: context.isDarkTheme
                                ? palette.textPrimary
                                : AppColors.text70,
                            fontSize: 12,
                            fontFamily: 'PingFang SC',
                            fontWeight: FontWeight.w500,
                            height: 1.50,
                            letterSpacing: 0.39,
                          ),
                        ),
                ],
              ),
            ),
            // ] else ...[
            //   Padding(
            //     // padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            //     padding: EdgeInsetsGeometry.zero,
            //     child: _buildTitle(),
            //   )
            // ],
            ],
          ),
        ),
      ),
    );
  }
}

Widget _buildTitle(BuildContext context) {
  final palette = context.omniPalette;
  return Row(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.center,
    children: [
      SvgPicture.asset(
        'assets/memory/star.svg',
        width: 12,
        height: 12,
        colorFilter: ColorFilter.mode(
          context.isDarkTheme ? palette.accentPrimary : AppColors.primaryBlue,
          BlendMode.srcIn,
        ),
        placeholderBuilder: (context) => Icon(
          Icons.bolt,
          size: 12,
          color: context.isDarkTheme
              ? palette.accentPrimary
              : AppColors.primaryBlue,
        ),
      ),
      const SizedBox(width: 8),
      Flexible(
        child: context.isDarkTheme
            ? Text(
                '小万的任务总结',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontFamily: AppTextStyles.fontFamily,
                  fontSize: AppTextStyles.fontSizeH3,
                  fontWeight: AppTextStyles.fontWeightMedium,
                  height: AppTextStyles.lineHeightH2,
                  letterSpacing: 0,
                  color: palette.textPrimary,
                ),
              )
            : GradientText(
                '小万的任务总结',
                style: const TextStyle(
                  fontFamily: AppTextStyles.fontFamily,
                  fontSize: AppTextStyles.fontSizeH3,
                  fontWeight: AppTextStyles.fontWeightMedium,
                  height: AppTextStyles.lineHeightH2,
                  letterSpacing: 0,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                colors: const [Color(0xFF2DA5F0), Color(0xFF1930D9)],
              ),
      ),
    ],
  );
}

class MemorySummary {
  final String title;
  final String tips;
  final String sum;

  MemorySummary({required this.title, required this.tips, required this.sum});
}
