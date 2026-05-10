import 'package:flutter/material.dart';
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
              leftButton?.text ??
                  (Localizations.localeOf(context).languageCode == 'en'
                      ? 'Cancel'
                      : '取消'),
              style: const TextStyle(
                fontSize: 16,
                color: Colors.black87,
              ),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: countdownAnimation != null
              ? AnimatedBuilder(
                  animation: countdownAnimation!,
                  builder: (context, child) {
                    final label =
                        rightButton?.text ??
                        (Localizations.localeOf(context).languageCode == 'en'
                            ? 'Confirm'
                            : '确认');
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
                    rightButton?.text ??
                        (Localizations.localeOf(context).languageCode == 'en'
                            ? 'Confirm'
                            : '确认'),
                    style: const TextStyle(
                      fontSize: 16,
                      color: Colors.white,
                    ),
                  ),
                ),
        ),
      ],
    );
  }
}
