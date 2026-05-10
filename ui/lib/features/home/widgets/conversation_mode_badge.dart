import 'package:flutter/material.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/theme/theme_context.dart';

class ConversationModeBadge extends StatelessWidget {
  const ConversationModeBadge({
    super.key,
    required this.mode,
    this.compact = false,
  });

  final ConversationMode mode;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final backgroundColor = context.isDarkTheme
        ? switch (mode) {
            ConversationMode.openclaw => Color.lerp(
              palette.surfaceSecondary,
              const Color(0xFFBA8757),
              0.2,
            )!,
            ConversationMode.chatOnly => Color.lerp(
              palette.surfaceSecondary,
              const Color(0xFF6E8CCB),
              0.18,
            )!,
            ConversationMode.subagent => Color.lerp(
              palette.surfaceSecondary,
              const Color(0xFF6E9F73),
              0.18,
            )!,
            ConversationMode.codex => Color.lerp(
              palette.surfaceSecondary,
              const Color(0xFF7D8A92),
              0.22,
            )!,
            ConversationMode.normal => Color.lerp(
              palette.surfaceSecondary,
              palette.accentPrimary,
              0.14,
            )!,
          }
        : switch (mode) {
            ConversationMode.openclaw => const Color(0xFFFBE7D3),
            ConversationMode.chatOnly => const Color(0xFFE7EEFF),
            ConversationMode.subagent => const Color(0xFFE8F6EF),
            ConversationMode.codex => const Color(0xFFE7ECEF),
            ConversationMode.normal => const Color(0xFFE6EEF9),
          };
    final textColor = context.isDarkTheme
        ? switch (mode) {
            ConversationMode.openclaw => const Color(0xFFE7D0B0),
            ConversationMode.chatOnly => const Color(0xFFD6E0FF),
            ConversationMode.subagent => const Color(0xFFD5E6D6),
            ConversationMode.codex => const Color(0xFFD9E0E4),
            ConversationMode.normal => Color.lerp(
              palette.textPrimary,
              palette.accentPrimary,
              0.38,
            )!,
          }
        : switch (mode) {
            ConversationMode.openclaw => const Color(0xFF9A540D),
            ConversationMode.chatOnly => const Color(0xFF3158B7),
            ConversationMode.subagent => const Color(0xFF167A49),
            ConversationMode.codex => const Color(0xFF46535B),
            ConversationMode.normal => const Color(0xFF2552A6),
          };
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 6 : 8,
        vertical: compact ? 2 : 4,
      ),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
        border: context.isDarkTheme
            ? Border.all(color: textColor.withValues(alpha: 0.16))
            : null,
      ),
      child: Text(
        mode.displayLabel,
        style: TextStyle(
          fontSize: compact ? 10 : 11,
          fontWeight: FontWeight.w600,
          color: textColor,
        ),
      ),
    );
  }
}
