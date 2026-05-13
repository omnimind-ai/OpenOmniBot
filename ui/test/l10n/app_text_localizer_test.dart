import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/app_text_localizer.dart';

void main() {
  tearDown(AppTextLocalizer.clearResolvedLocale);

  test('uses active locale override for source-text translations', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(AppTextLocalizer.text('设置'), '设置');

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(AppTextLocalizer.text('设置'), 'Settings');
  });

  test('keeps floating overlay source texts localized after migration', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(AppTextLocalizer.text('当前没有后端任务'), '当前没有后端任务');
    expect(AppTextLocalizer.text('当前没有任何 Agent'), '当前没有任何 Agent');
    expect(
      AppTextLocalizer.text('Agent 后端空闲。轻点打开管理面板。'),
      'Agent 后端空闲。轻点打开管理面板。',
    );

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(AppTextLocalizer.text('当前没有后端任务'), 'No backend tasks running');
    expect(AppTextLocalizer.text('当前没有任何 Agent'), 'No Agents running');
    expect(
      AppTextLocalizer.text('Agent 后端空闲。轻点打开管理面板。'),
      'Agent backend idle. Tap to open manager.',
    );
  });
}
