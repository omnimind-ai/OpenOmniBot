import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/cache.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, null);
  });

  testWidgets('renders all files access entry and opens native settings', (
    tester,
  ) async {
    final permissionCalls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, (call) async {
          if (call.method == 'doMMKVDecodeBoole') {
            return true;
          }
          return null;
        });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          permissionCalls.add(call);
          switch (call.method) {
            case 'isBackgroundRunAllowed':
            case 'isOverlayPermission':
            case 'isInstalledAppsPermissionGranted':
            case 'isAccessibilityServiceEnabled':
              return true;
            case 'isPublicStorageAccessGranted':
              return false;
            case 'getShizukuStatus':
              return <String, dynamic>{
                'status': 'NOT_INSTALLED',
                'backend': 'NONE',
                'installed': false,
                'running': false,
                'permissionGranted': false,
                'binderReady': false,
                'serviceBound': false,
                'availableActions': <String>[],
                'message': '',
              };
            case 'openPublicStorageSettings':
              return true;
          }
          return null;
        });

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        theme: AppTheme.lightTheme,
        home: const AuthorizeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();

    final entry = find.text('所有文件访问权限');
    await tester.scrollUntilVisible(entry, 120);
    await tester.pumpAndSettle();

    expect(entry, findsOneWidget);
    expect(find.textContaining('公共存储'), findsOneWidget);

    await tester.tap(entry);
    await tester.pump();

    expect(
      permissionCalls.map((call) => call.method),
      contains('openPublicStorageSettings'),
    );
  });
}
