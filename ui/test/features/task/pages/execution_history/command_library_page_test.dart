import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/command_library_page.dart';
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

  testWidgets('Function library groups same semantic assets and opens detail', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'listOobReusableFunctions') {
            return <String, dynamic>{
              'success': true,
              'count': 3,
              'functions': <Map<String, dynamic>>[
                <String, dynamic>{
                  'function_id': 'open_settings_a',
                  'name': '打开 Settings',
                  'description': '打开 Android 设置',
                  'card_count': 4,
                  'step_count': 3,
                  'parameter_names': <String>['package_name'],
                  'registered_at': '1700000000000',
                  'source_run_ids': <String>['run-1'],
                  'run_stats': <String, dynamic>{
                    'run_count': 2,
                    'success_count': 2,
                    'fail_count': 0,
                  },
                  'step_summaries': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'index': 0,
                      'title': '打开 Settings',
                      'kind': 'omniflow_action',
                      'executor': 'omniflow',
                      'tool': 'open_app',
                    },
                  ],
                },
                <String, dynamic>{
                  'function_id': 'debug_40df4acf',
                  'name': 'Debug VLM RunLog',
                  'description': '打开 Android 设置',
                  'card_count': 4,
                  'step_count': 3,
                  'parameter_names': <String>['package_name'],
                  'registered_at': '1700000005000',
                  'source_run_ids': <String>['run-2'],
                  'run_stats': <String, dynamic>{
                    'run_count': 1,
                    'success_count': 1,
                    'fail_count': 0,
                  },
                  'step_summaries': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'index': 0,
                      'title': '打开 Settings',
                      'kind': 'omniflow_action',
                      'executor': 'omniflow',
                      'tool': 'open_app',
                    },
                  ],
                },
                <String, dynamic>{
                  'function_id': 'enable_wifi',
                  'name': '打开 WiFi',
                  'description': '启用 WiFi',
                  'card_count': 2,
                  'step_count': 2,
                  'parameter_names': <String>[],
                  'registered_at': '1700000002000',
                  'source_run_ids': <String>['run-3'],
                  'run_stats': <String, dynamic>{
                    'run_count': 4,
                    'success_count': 4,
                    'fail_count': 0,
                  },
                  'step_summaries': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'index': 0,
                      'title': '打开 WiFi',
                      'kind': 'omniflow_action',
                      'executor': 'omniflow',
                      'tool': 'open_app',
                    },
                    <String, dynamic>{
                      'index': 1,
                      'title': '确认开关',
                      'kind': 'tool_call',
                      'executor': 'tool',
                      'tool': 'click',
                    },
                  ],
                },
              ],
            };
          }
          if (call.method == 'getOobReusableFunction') {
            return <String, dynamic>{
              'success': true,
              'function_id': 'open_settings_a',
              'name': '打开 Settings',
              'description': '打开 Android 设置',
              'parameters': <Map<String, dynamic>>[
                <String, dynamic>{
                  'name': 'package_name',
                  'type': 'string',
                  'required': false,
                  'description': 'Android package name',
                  'default': 'com.android.settings',
                },
              ],
              'execution': <String, dynamic>{
                'step_count': 1,
                'steps': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'step_1',
                    'index': 0,
                    'title': '打开 Settings',
                    'kind': 'omniflow_action',
                    'executor': 'omniflow',
                    'tool': 'open_app',
                  },
                ],
              },
            };
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: CommandLibraryPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Function 库'), findsOneWidget);
    expect(find.text('打开 Settings'), findsOneWidget);
    expect(find.text('Debug VLM RunLog'), findsNothing);
    expect(find.text('打开 WiFi'), findsOneWidget);
    expect(find.text('类型 OmniFlow'), findsWidgets);
    expect(find.text('步骤 3'), findsOneWidget);
    expect(find.text('参数 1'), findsOneWidget);
    expect(find.text('变体 2'), findsOneWidget);
    expect(find.text('package_name'), findsOneWidget);
    expect(find.textContaining('1. 打开 Settings · open_app'), findsOneWidget);
    expect(find.textContaining('卡片'), findsNothing);
    expect(find.textContaining('执行次数'), findsNothing);
    expect(find.textContaining('创建时间'), findsNothing);
    expect(find.textContaining('来源'), findsNothing);
    expect(find.textContaining('oob_cmd'), findsNothing);
    expect(find.textContaining('run-1'), findsNothing);
    expect(find.textContaining('Command'), findsNothing);
    expect(find.textContaining('指令'), findsNothing);

    await tester.tap(find.text('打开 Settings').first);
    await tester.pumpAndSettle();

    expect(find.text('Function 详情'), findsOneWidget);
    expect(find.text('动作预览'), findsOneWidget);
    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('参数'), findsOneWidget);
    expect(find.textContaining('open_app · OmniFlow'), findsOneWidget);
  });

  testWidgets(
    'Function run button invokes reusable function and shows running state',
    (tester) async {
      final runCompleter = Completer<Map<String, dynamic>>();
      var runCalls = 0;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'listOobReusableFunctions') {
              return <String, dynamic>{
                'success': true,
                'count': 1,
                'functions': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'function_id': 'open_settings',
                    'name': '打开 Settings',
                    'description': '打开 Android 设置',
                    'step_count': 1,
                    'parameter_names': <String>['package_name'],
                    'registered_at': '1700000000000',
                    'step_summaries': <Map<String, dynamic>>[
                      <String, dynamic>{
                        'index': 0,
                        'title': '打开 Settings',
                        'kind': 'omniflow_action',
                        'executor': 'omniflow',
                        'tool': 'open_app',
                      },
                    ],
                  },
                ],
              };
            }
            if (call.method == 'runOobReusableFunction') {
              runCalls += 1;
              return runCompleter.future;
            }
            return null;
          });

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: CommandLibraryPage(),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('执行 Function'));
      await tester.pump();

      expect(runCalls, 1);
      expect(find.text('执行中'), findsOneWidget);

      runCompleter.complete(<String, dynamic>{
        'success': true,
        'function_id': 'open_settings',
        'goal': 'oob_reusable_function_run:open_settings',
        'terminal_state': <String, dynamic>{'status': 'completed'},
      });
      await tester.pumpAndSettle();

      expect(find.text('执行 Function'), findsOneWidget);
    },
  );
}
