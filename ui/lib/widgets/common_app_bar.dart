import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/ai_generated_badge.dart';

/// 通用 AppBar 组件
///
/// 统一应用内页面的顶部导航栏样式，支持多种展示模式：
/// - 简单返回模式：仅显示返回按钮
/// - 标题模式：显示居中标题 + 返回按钮
/// - 带 AI 标签模式：标题 + AI 标签 + 返回按钮
/// - 完整模式：标题 + AI 标签 + 返回按钮 + 右侧操作
class CommonAppBar extends StatelessWidget implements PreferredSizeWidget {
  /// 标题文字（可选，不传则不显示标题区域）
  final String? title;

  /// 自定义标题区域（优先级高于 title）
  final Widget? titleWidget;

  /// 是否显示 AI 生成标签（仅在有标题时生效）
  final bool showAiBadge;

  /// 返回按钮点击回调（默认执行 GoRouterManager.pop()）
  final VoidCallback? onBackPressed;

  /// 右侧操作区域（可选）
  final Widget? trailing;

  /// 右侧操作列表（优先级高于 trailing）
  final List<Widget>? actions;

  /// 是否显示默认返回按钮
  final bool showLeading;

  /// 自定义左侧区域
  final Widget? leading;

  /// 左侧区域宽度
  final double? leadingWidth;

  /// 返回图标颜色
  final Color? backIconColor;

  /// 标题文字样式（可选，有默认值）
  final TextStyle? titleStyle;

  /// AppBar 高度
  final double height;

  /// 背景色
  final Color? backgroundColor;

  /// 是否作为 Scaffold.appBar 使用
  final bool primary;

  /// 标题是否居中
  final bool centerTitle;

  const CommonAppBar({
    super.key,
    this.title,
    this.titleWidget,
    this.showAiBadge = false,
    this.onBackPressed,
    this.trailing,
    this.actions,
    this.showLeading = true,
    this.leading,
    this.leadingWidth,
    this.backIconColor,
    this.titleStyle,
    this.height = 44,
    this.backgroundColor,
    this.primary = false,
    this.centerTitle = true,
  });

  @override
  Size get preferredSize => Size.fromHeight(height);

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final resolvedTitleColor = titleStyle?.color ?? palette.textPrimary;
    final resolvedBackIconColor = backIconColor ?? resolvedTitleColor;
    final resolvedBackgroundColor =
        backgroundColor ?? Theme.of(context).appBarTheme.backgroundColor;
    final resolvedLeading =
        leading ??
        (showLeading
            ? Center(
                child: GestureDetector(
                  onTap: onBackPressed ?? () => GoRouterManager.pop(),
                  child: SizedBox(
                    width: 24,
                    height: 24,
                    child: SvgPicture.asset(
                      'assets/common/chevron_left.svg',
                      width: 24,
                      height: 24,
                      colorFilter: ColorFilter.mode(
                        resolvedBackIconColor,
                        BlendMode.srcIn,
                      ),
                    ),
                  ),
                ),
              )
            : null);

    final resolvedActions = actions ?? (trailing == null ? null : [trailing!]);

    return AppBar(
      primary: primary,
      automaticallyImplyLeading: false,
      toolbarHeight: height,
      backgroundColor: resolvedBackgroundColor,
      elevation: 0,
      scrolledUnderElevation: 0,
      surfaceTintColor: resolvedBackgroundColor,
      shadowColor: Colors.transparent,
      leadingWidth: resolvedLeading == null ? leadingWidth : leadingWidth ?? 56,
      leading: resolvedLeading,
      centerTitle: centerTitle,
      title:
          titleWidget ??
          (title == null
              ? null
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      context.trText(title!),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style:
                          titleStyle ??
                          TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: palette.textPrimary,
                            fontFamily: 'SF Pro',
                          ),
                    ),
                    if (showAiBadge) const AiGeneratedBadge(),
                  ],
                )),
      actions: resolvedActions,
    );
  }
}
