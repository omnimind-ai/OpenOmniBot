import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/app_update');

  tearDown(() async {
    AppUpdateService.betaOptInNotifier.value = false;
    AppUpdateService.downloadSourceNotifier.value =
        AppUpdateDownloadSource.worker;
    AppUpdateService.statusNotifier.value = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('checkNow updates status notifier from channel response', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
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

    final status = await AppUpdateService.checkNow();

    expect(status, isNotNull);
    expect(status!.hasUpdate, isTrue);
    expect(AppUpdateService.statusNotifier.value?.latestVersion, '0.0.2');
  });

  test('download source defaults legacy cnb to worker', () {
    expect(
      AppUpdateDownloadSource.fromRaw(null),
      AppUpdateDownloadSource.worker,
    );
    expect(
      AppUpdateDownloadSource.fromRaw('cnb'),
      AppUpdateDownloadSource.worker,
    );
    expect(
      AppUpdateDownloadSource.fromRaw('github'),
      AppUpdateDownloadSource.github,
    );
  });

  test('setBetaOptIn updates notifier and refreshes status', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'setBetaOptIn') {
            return call.arguments['enabled'] == true;
          }
          if (call.method == 'checkNow') {
            return <String, dynamic>{
              'currentVersion': '1.6.1',
              'latestVersion': '1.6.1.2',
              'hasUpdate': true,
              'checkedAt': 3,
              'publishedAt': 4,
              'releaseUrl': 'https://example.com/release',
              'releaseNotes': 'beta notes',
              'apkName': 'OpenOmniBot-v1.6.1.2.apk',
              'apkDownloadUrl': 'https://example.com/app.apk',
            };
          }
          return null;
        });

    final enabled = await AppUpdateService.setBetaOptIn(true);

    expect(enabled, isTrue);
    expect(AppUpdateService.betaOptInNotifier.value, isTrue);
    expect(AppUpdateService.statusNotifier.value?.latestVersion, '1.6.1.2');
  });

  test(
    'setDownloadSource updates notifier and refreshes cached status',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'setApkDownloadSource') {
              return call.arguments['source'] as String?;
            }
            if (call.method == 'getCachedStatus') {
              return <String, dynamic>{
                'currentVersion': '1.6.1',
                'latestVersion': '1.6.2',
                'hasUpdate': true,
                'checkedAt': 5,
                'publishedAt': 6,
                'releaseUrl': 'https://example.com/release',
                'releaseNotes': 'stable notes',
                'apkName': 'OpenOmniBot-v1.6.2.apk',
                'apkDownloadUrl':
                    'https://github.com/omnimind-ai/OpenOmniBot/releases/download/v1.6.2/OpenOmniBot-v1.6.2.apk',
              };
            }
            return null;
          });

      final source = await AppUpdateService.setDownloadSource(
        AppUpdateDownloadSource.github,
      );

      expect(source, AppUpdateDownloadSource.github);
      expect(
        AppUpdateService.downloadSourceNotifier.value,
        AppUpdateDownloadSource.github,
      );
      expect(AppUpdateService.statusNotifier.value?.latestVersion, '1.6.2');
    },
  );

  test('dismissBanner hides the banner for the same version only', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();

    const status = AppUpdateStatus(
      currentVersion: '0.0.1',
      latestVersion: '0.0.3',
      hasUpdate: true,
      checkedAt: 1,
      publishedAt: 2,
      releaseUrl: 'https://example.com/release',
      releaseNotes: 'notes',
      apkName: 'OpenOmniBot-v0.0.3.apk',
      apkDownloadUrl: 'https://example.com/app.apk',
    );

    expect(AppUpdateService.shouldShowBanner(status), isTrue);

    await AppUpdateService.dismissBanner(status);

    expect(AppUpdateService.shouldShowBanner(status), isFalse);
    expect(
      AppUpdateService.shouldShowBanner(
        const AppUpdateStatus(
          currentVersion: '0.0.1',
          latestVersion: '0.0.4',
          hasUpdate: true,
          checkedAt: 1,
          publishedAt: 2,
          releaseUrl: 'https://example.com/release',
          releaseNotes: 'notes',
          apkName: 'OpenOmniBot-v0.0.4.apk',
          apkDownloadUrl: 'https://example.com/app.apk',
        ),
      ),
      isTrue,
    );
  });
}
