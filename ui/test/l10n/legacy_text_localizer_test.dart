import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  test('uses active locale override for legacy translations', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(LegacyTextLocalizer.localize('设置'), '设置');

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(LegacyTextLocalizer.localize('设置'), 'Settings');
  });
}
