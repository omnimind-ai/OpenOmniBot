import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';

class AppAccessibility {
  const AppAccessibility._();

  static bool isEnglish(BuildContext context) {
    return Localizations.localeOf(context).languageCode == 'en';
  }

  static TextDirection directionOf(BuildContext context) {
    return Directionality.maybeOf(context) ?? TextDirection.ltr;
  }

  static Future<void> announce(BuildContext context, String message) async {
    final trimmed = message.trim();
    if (trimmed.isEmpty) {
      return;
    }
    await SemanticsService.announce(trimmed, directionOf(context));
  }

  static String toggleStateLabel(
    BuildContext context,
    bool enabled, {
    String zhEnabled = '已开启',
    String zhDisabled = '已关闭',
    String enEnabled = 'On',
    String enDisabled = 'Off',
  }) {
    if (isEnglish(context)) {
      return enabled ? enEnabled : enDisabled;
    }
    return enabled ? zhEnabled : zhDisabled;
  }

  static String localizedText(
    BuildContext context, {
    required String zh,
    required String en,
  }) {
    return isEnglish(context) ? en : zh;
  }
}
