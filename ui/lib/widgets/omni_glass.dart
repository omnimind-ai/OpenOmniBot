import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';

class OmniGlassPanel extends StatelessWidget {
  const OmniGlassPanel({
    super.key,
    required this.child,
    this.borderRadius = const BorderRadius.all(Radius.circular(24)),
    this.padding = EdgeInsets.zero,
    this.width,
    this.height,
    this.forceDark = false,
  });

  final Widget child;
  final BorderRadius borderRadius;
  final EdgeInsetsGeometry padding;
  final double? width;
  final double? height;
  final bool forceDark;

  @override
  Widget build(BuildContext context) {
    final palette = forceDark ? OmniThemePalette.dark : context.omniPalette;
    final isDark = forceDark || context.isDarkTheme;
    final topTint = isDark
        ? palette.surfacePrimary.withValues(alpha: 0.26)
        : Colors.white.withValues(alpha: 0.40);
    final bottomTint = isDark
        ? palette.surfaceSecondary.withValues(alpha: 0.12)
        : Colors.white.withValues(alpha: 0.18);
    final borderColor = isDark
        ? Colors.white.withValues(alpha: 0.22)
        : Colors.white.withValues(alpha: 0.82);
    final highlightColor = isDark
        ? Colors.white.withValues(alpha: 0.30)
        : Colors.white.withValues(alpha: 0.86);
    final accentGlow = palette.accentPrimary.withValues(
      alpha: isDark ? 0.10 : 0.08,
    );

    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        borderRadius: borderRadius,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: isDark ? 0.34 : 0.12),
            blurRadius: isDark ? 42 : 30,
            offset: const Offset(0, 18),
          ),
          BoxShadow(
            color: accentGlow,
            blurRadius: 28,
            offset: const Offset(0, -8),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: borderRadius,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
          child: DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: borderRadius,
              border: Border.all(color: borderColor),
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [topTint, bottomTint],
              ),
            ),
            child: Stack(
              children: [
                Positioned(
                  left: 18,
                  right: 18,
                  top: 0,
                  child: Container(
                    height: 1,
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [
                          Colors.transparent,
                          highlightColor,
                          Colors.transparent,
                        ],
                      ),
                    ),
                  ),
                ),
                Padding(padding: padding, child: child),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
