import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_list_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('RunLog list shows explicit running success and failed states', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogs') {
            return <String, dynamic>{
              'success': true,
              'count': 3,
              'provider': 'internal_oob',
              'runs': <Map<String, dynamic>>[
                <String, dynamic>{
                  'run_id': 'run-running',
                  'goal': '打开 Bluetooth',
                  'run_finished': false,
                  'run_status': 'running',
                  'success': false,
                  'step_count': 1,
                  'started_at_ms': 1700000000000,
                  'tool_name': 'open_app',
                },
                <String, dynamic>{
                  'run_id': 'run-success',
                  'goal': '打开 Settings',
                  'run_finished': true,
                  'run_success': true,
                  'run_status': 'success',
                  'success': true,
                  'step_count': 2,
                  'duration_ms': 1234,
                  'token_usage_total': 31038,
                  'started_at_ms': 1700000000000,
                  'finished_at_ms': 1700000001234,
                },
                <String, dynamic>{
                  'run_id': 'run-failed',
                  'goal': '打开 Wi-Fi',
                  'run_finished': true,
                  'run_success': false,
                  'run_status': 'failed',
                  'success': false,
                  'step_count': 1,
                  'error_message': '找不到可执行动作',
                  'started_at_ms': 1700000000000,
                  'finished_at_ms': 1700000001000,
                },
              ],
            };
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: RunLogListPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('打开 Bluetooth'), findsOneWidget);
    expect(find.text('运行中'), findsOneWidget);
    expect(find.text('打开 Settings'), findsOneWidget);
    expect(find.text('已完成'), findsOneWidget);
    expect(find.text('打开 Wi-Fi'), findsOneWidget);
    expect(find.text('失败'), findsOneWidget);
    expect(find.text('找不到可执行动作'), findsOneWidget);
    expect(find.textContaining('31.0k tokens'), findsOneWidget);
  });
}
