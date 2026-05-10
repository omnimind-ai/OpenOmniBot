import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';

class HomeDrawerSearchField extends StatelessWidget {
  const HomeDrawerSearchField({
    super.key,
    required this.controller,
    required this.focusNode,
    required this.isSearching,
    required this.textColor,
    this.hintText,
  });

  final TextEditingController controller;
  final FocusNode focusNode;
  final bool isSearching;
  final Color textColor;
  final String? hintText;

  bool get _hasQuery => controller.text.trim().isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final hasFocus = focusNode.hasFocus;
    final iconColor = hasFocus ? palette.accentPrimary : palette.textSecondary;
    final backgroundColor = context.isDarkTheme
        ? hasFocus
              ? Color.lerp(
                  palette.surfaceSecondary,
                  palette.surfaceElevated,
                  0.9,
                )!
              : palette.surfaceSecondary
        : hasFocus
        ? Colors.white
        : palette.previewFallback;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOutCubic,
      height: 36,
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(18),
        boxShadow: context.isDarkTheme
            ? [
                BoxShadow(
                  color: Colors.black.withValues(alpha: hasFocus ? 0.18 : 0.12),
                  blurRadius: hasFocus ? 14 : 10,
                  offset: const Offset(0, 4),
                ),
                if (hasFocus)
                  BoxShadow(
                    color: palette.accentPrimary.withValues(alpha: 0.12),
                    blurRadius: 12,
                    spreadRadius: -2,
                  ),
              ]
            : [
                BoxShadow(
                  color: palette.shadowColor.withValues(
                    alpha: hasFocus ? 0.18 : 0.08,
                  ),
                  blurRadius: hasFocus ? 14 : 10,
                  offset: const Offset(0, 3),
                ),
              ],
      ),
      child: TextField(
        controller: controller,
        focusNode: focusNode,
        textInputAction: TextInputAction.search,
        style: TextStyle(
          fontSize: 13,
          color: textColor,
          fontWeight: FontWeight.w500,
          height: 1.2,
          fontFamily: 'PingFang SC',
        ),
        cursorColor: palette.accentPrimary,
        decoration: InputDecoration(
          hintText: hintText ?? context.l10n.homeDrawerSearchHint,
          hintStyle: TextStyle(
            fontSize: 13,
            color: palette.textTertiary,
            fontWeight: FontWeight.w400,
            fontFamily: 'PingFang SC',
          ),
          border: InputBorder.none,
          enabledBorder: InputBorder.none,
          focusedBorder: InputBorder.none,
          disabledBorder: InputBorder.none,
          errorBorder: InputBorder.none,
          focusedErrorBorder: InputBorder.none,
          filled: false,
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(vertical: 9),
          prefixIconConstraints: const BoxConstraints(
            minWidth: 38,
            minHeight: 36,
          ),
          suffixIconConstraints: const BoxConstraints(
            minWidth: 34,
            minHeight: 36,
          ),
          prefixIcon: Icon(Icons.search_rounded, size: 18, color: iconColor),
          suffixIcon: isSearching
              ? Padding(
                  padding: const EdgeInsets.all(10),
                  child: SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        palette.accentPrimary,
                      ),
                    ),
                  ),
                )
              : _hasQuery
              ? IconButton(
                  tooltip: context.l10n.homeDrawerClearSearch,
                  splashRadius: 16,
                  onPressed: controller.clear,
                  icon: Icon(
                    Icons.close_rounded,
                    size: 16,
                    color: palette.textSecondary,
                  ),
                )
              : null,
        ),
      ),
    );
  }
}
