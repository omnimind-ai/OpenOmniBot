import 'package:flutter/material.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/generated/app_localizations_zh.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

extension AppL10nBuildContextX on BuildContext {
  AppLocalizations get l10n =>
      AppLocalizations.of(this) ?? AppLocalizationsZh();

  String trLegacy(String text) {
    final resolvedLocale = AppLocalizations.of(this) == null
        ? const Locale('zh')
        : Localizations.localeOf(this);
    return LegacyTextLocalizer.localize(text, locale: resolvedLocale);
  }
}
