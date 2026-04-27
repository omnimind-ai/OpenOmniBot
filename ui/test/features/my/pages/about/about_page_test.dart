import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/my/pages/about/about_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/app_update_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const deviceChannel = MethodChannel('device_info');
  const updateChannel = MethodChannel('cn.com.omnimind.bot/app_update');

  tearDown(() async {
    AppUpdateService.betaOptInNotifier.value = false;
    AppUpdateService.downloadSourceNotifier.value = AppUpdateDownloadSource.cnb;
    AppUpdateService.statusNotifier.value = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, null);
  });

  testWidgets('renders version and update hint from services', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
          if (call.method == 'getBetaOptIn') {
            return false;
          }
          if (call.method == 'getApkDownloadSource') {
            return 'cnb';
          }
          if (call.method == 'getCachedStatus') {
            return <String, dynamic>{
              'currentVersion': '0.0.1',
              'latestVersion': '0.0.2',
              'hasUpdate': true,
              'checkedAt': 1,
              'publishedAt': 2,
              'releaseUrl': 'https://example.com/release',
              'releaseNotes': 'notes',
              'apkName': 'OpenOmniBot-v0.0.2.apk',
              'apkDownloadUrl': 'https://example.com/app.apk',
            };
          }
          if (call.method == 'checkNow') {
            return <String, dynamic>{
              'currentVersion': '0.0.1',
              'latestVersion': '0.0.2',
              'hasUpdate': true,
              'checkedAt': 1,
              'publishedAt': 2,
              'releaseUrl': 'https://example.com/release',
              'releaseNotes': 'notes',
              'apkName': 'OpenOmniBot-v0.0.2.apk',
              'apkDownloadUrl': 'https://example.com/app.apk',
            };
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AboutPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(SingleChildScrollView), findsNothing);
    expect(find.text('Version 0.0.1'), findsOneWidget);
    expect(find.text('加入 beta 测试'), findsOneWidget);
    expect(find.text('安装包下载源'), findsOneWidget);
    expect(find.text('CNB'), findsWidgets);
    expect(find.textContaining('发现新版本'), findsOneWidget);
    expect(find.text('查看新版本'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('about-download-source-dropdown')),
    );
    await tester.pumpAndSettle();

    expect(find.text('国内网络优先'), findsOneWidget);
    expect(find.text('官方 Release'), findsOneWidget);
  });
}
