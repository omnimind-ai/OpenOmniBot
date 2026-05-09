import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';

class ConversationStatusIndicator extends StatelessWidget {
  const ConversationStatusIndicator({
    super.key,
    required this.isRunning,
    this.compact = false,
  });

  final bool isRunning;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final accentColor = palette.accentPrimary;
    final idleColor = palette.textTertiary.withValues(alpha: 0.62);
    final size = compact ? 7.0 : 8.0;
    final ringSize = compact ? 11.0 : 12.0;
    final label = isRunning
        ? context.l10n.conversationStatusRunning
        : context.l10n.conversationStatusCompleted;

    return Tooltip(
      message: label,
      child: Semantics(
        label: label,
        child: SizedBox(
          width: ringSize,
          height: ringSize,
          child: Center(
            child: AnimatedContainer(
              key: ValueKey<String>(
                isRunning
                    ? 'conversation-status-running'
                    : 'conversation-status-completed',
              ),
              duration: const Duration(milliseconds: 180),
              width: isRunning ? ringSize : size,
              height: isRunning ? ringSize : size,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isRunning ? Colors.transparent : idleColor,
                border: isRunning
                    ? Border.all(
                        color: accentColor.withValues(alpha: 0.9),
                        width: compact ? 1.4 : 1.6,
                      )
                    : null,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
