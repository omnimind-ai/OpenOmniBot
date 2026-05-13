import 'dart:ui';

import 'package:ui/l10n/app_text_localizer.dart';

@Deprecated('Use AppTextLocalizer instead.')
class LegacyTextLocalizer {
  static void setResolvedLocale(Locale locale) {
    AppTextLocalizer.setResolvedLocale(locale);
  }

  static void clearResolvedLocale() {
    AppTextLocalizer.clearResolvedLocale();
  }

  static bool get isEnglish => AppTextLocalizer.choose(zh: false, en: true);

  static String localize(String text, {Locale? locale}) {
    return AppTextLocalizer.text(text, locale: locale);
  }
}
