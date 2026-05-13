import 'package:flutter/material.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import '../models/block_models.dart';

class ButtonsGroupTwo extends StatelessWidget {
  final Animation<int>? countdownAnimation;
  final bool isExecuting;
  final ButtonModel? leftButton;
  final ButtonModel? rightButton;
  final Function(ButtonModel)? onButtonPressed;

  const ButtonsGroupTwo({
    Key? key,
    this.countdownAnimation,
    this.isExecuting = false,
    this.leftButton,
    this.rightButton,
    this.onButtonPressed,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final defaultCancelText = AppTextLocalizer.text('取消', locale: locale);
    final defaultConfirmText = AppTextLocalizer.text('确认', locale: locale);
    return Row(
      children: [
        Expanded(
          child: OutlinedButton(
            onPressed: () {
              if (leftButton != null) {
                onButtonPressed?.call(leftButton!);
              }
            },
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 12),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
              ),
            ),
            child: Text(
              leftButton?.text ?? defaultCancelText,
              style: const TextStyle(fontSize: 16, color: Colors.black87),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: countdownAnimation != null
              ? AnimatedBuilder(
                  animation: countdownAnimation!,
                  builder: (context, child) {
                    final label = rightButton?.text ?? defaultConfirmText;
                    final text = isExecuting
                        ? '$label${countdownAnimation!.value}s'
                        : label;
                    return ElevatedButton(
                      onPressed: () {
                        if (rightButton != null) {
                          onButtonPressed?.call(rightButton!);
                        }
                      },
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.black87,
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(8),
                        ),
                      ),
                      child: Text(
                        text,
                        style: const TextStyle(
                          fontSize: 16,
                          color: Colors.white,
                        ),
                      ),
                    );
                  },
                )
              : ElevatedButton(
                  onPressed: () {
                    if (rightButton != null) {
                      onButtonPressed?.call(rightButton!);
                    }
                  },
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.black87,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                  child: Text(
                    rightButton?.text ?? defaultConfirmText,
                    style: const TextStyle(fontSize: 16, color: Colors.white),
                  ),
                ),
        ),
      ],
    );
  }
}
