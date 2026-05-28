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

  testWidgets(
    'Reusable command library groups same semantic assets and opens detail',
    (tester) async {
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

      expect(find.text('复用指令库'), findsOneWidget);
      expect(find.text('打开 Settings'), findsOneWidget);
      expect(find.text('Debug VLM RunLog'), findsNothing);
      expect(find.text('打开 WiFi'), findsOneWidget);
      expect(find.text('类型 OmniFlow'), findsNothing);
      expect(find.text('状态 已注册'), findsNothing);
      expect(find.text('步骤 3'), findsOneWidget);
      expect(find.text('参数 1'), findsOneWidget);
      expect(find.text('RunLogs 2'), findsOneWidget);
      expect(find.text('变体 2'), findsNothing);
      expect(find.textContaining('来自 2 条 RunLog'), findsNothing);
      expect(find.text('package_name'), findsNothing);
      expect(find.textContaining('1. 打开 Settings'), findsOneWidget);
      expect(find.textContaining('open_app'), findsNothing);
      expect(find.textContaining('卡片'), findsNothing);
      expect(find.textContaining('执行次数'), findsNothing);
      expect(find.textContaining('创建时间'), findsNothing);
      expect(find.textContaining('来源'), findsNothing);
      expect(find.textContaining('oob_cmd'), findsNothing);
      expect(find.textContaining('run-1'), findsNothing);
      expect(find.textContaining('Command'), findsNothing);

      await tester.tap(find.byIcon(Icons.info_outline_rounded).first);
      await tester.pumpAndSettle();

      expect(find.text('复用指令详情'), findsOneWidget);
      expect(find.text('类型 OmniFlow'), findsNothing);
      expect(find.text('状态 已注册'), findsOneWidget);
      expect(find.text('package_name'), findsOneWidget);
      expect(find.text('离线来源'), findsOneWidget);
      expect(find.textContaining('由 RunLog 注册'), findsOneWidget);
      expect(find.text('动作预览'), findsOneWidget);
      expect(find.text('步骤'), findsOneWidget);
      expect(find.text('参数'), findsOneWidget);
      expect(find.textContaining('打开 Settings · 打开应用'), findsOneWidget);
      expect(find.textContaining('open_app'), findsNothing);
      expect(find.textContaining('OmniFlow'), findsNothing);
    },
  );

  testWidgets('Reusable command step can be edited and saved', (tester) async {
    Map<String, dynamic>? savedSpec;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'listOobReusableFunctions') {
            return <String, dynamic>{
              'success': true,
              'functions': <Map<String, dynamic>>[
                <String, dynamic>{
                  'function_id': 'search_contact',
                  'name': '搜索联系人',
                  'description': '搜索联系人',
                  'step_count': 1,
                  'parameter_names': <String>['query'],
                  'step_summaries': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'id': 'step_1',
                      'index': 0,
                      'title': '输入文本',
                      'kind': 'omniflow_action',
                      'executor': 'omniflow',
                      'tool': 'type',
                    },
                  ],
                },
              ],
            };
          }
          if (call.method == 'getOobReusableFunction') {
            return <String, dynamic>{
              'function_id': 'search_contact',
              'name': '搜索联系人',
              'parameters': <String, dynamic>{
                'type': 'object',
                'properties': <String, dynamic>{
                  'query': <String, dynamic>{
                    'type': 'string',
                    'default': '妈妈',
                    'x_oob_bindings': <String>[
                      r'$.execution.steps[0].args.text',
                      r'$.actions[0].text',
                    ],
                  },
                },
              },
              'actions': <Map<String, dynamic>>[
                <String, dynamic>{
                  'type': 'type',
                  'text': r'${query}',
                  'description': '输入文本',
                },
              ],
              'execution': <String, dynamic>{
                'step_count': 1,
                'steps': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'step_1',
                    'index': 0,
                    'title': '输入文本',
                    'kind': 'omniflow_action',
                    'executor': 'omniflow',
                    'tool': 'type',
                    'omniflow_action': 'type',
                    'callable_tool': 'type',
                    'args': <String, dynamic>{'text': '妈妈'},
                  },
                ],
              },
            };
          }
          if (call.method == 'registerOobReusableFunction') {
            final arguments = Map<String, dynamic>.from(call.arguments as Map);
            savedSpec = Map<String, dynamic>.from(
              arguments['functionSpec'] as Map,
            );
            return <String, dynamic>{
              'success': true,
              'function_id': 'search_contact',
              'created_function_id': 'search_contact',
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
    await tester.tap(find.byIcon(Icons.info_outline_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.edit_outlined));
    await tester.pumpAndSettle();

    final fields = find.byType(TextField);
    expect(fields, findsNWidgets(3));
    await tester.enterText(fields.at(0), '输入姓名');
    await tester.enterText(fields.at(1), 'type');
    await tester.enterText(fields.at(2), '{"text":"妈妈的新号码"}');
    await tester.tap(find.text('保存').last);
    await tester.pumpAndSettle();

    final execution = savedSpec?['execution'] as Map;
    final step = (execution['steps'] as List).single as Map;
    expect(step['title'], '输入姓名');
    expect(step['tool'], 'input_text');
    expect(step['omniflow_action'], 'input_text');
    expect((step['args'] as Map)['text'], '妈妈的新号码');
    final action = (savedSpec?['actions'] as List).single as Map;
    expect(action['type'], 'input_text');
    expect(action['text'], r'${query}');
    expect(action['description'], '输入姓名');
    final properties = ((savedSpec?['parameters'] as Map)['properties'] as Map);
    expect((properties['query'] as Map)['default'], '妈妈的新号码');
  });

  testWidgets('Deleting a recorded step reindexes parameter bindings', (
    tester,
  ) async {
    Map<String, dynamic>? savedSpec;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'listOobReusableFunctions') {
            return <String, dynamic>{
              'success': true,
              'functions': <Map<String, dynamic>>[
                <String, dynamic>{
                  'function_id': 'duplicate_type',
                  'name': '输入联系人',
                  'step_count': 2,
                  'step_summaries': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'id': 'step_1',
                      'index': 0,
                      'title': '错误输入',
                      'executor': 'omniflow',
                      'tool': 'input_text',
                    },
                    <String, dynamic>{
                      'id': 'step_2',
                      'index': 1,
                      'title': '有效输入',
                      'executor': 'omniflow',
                      'tool': 'input_text',
                    },
                  ],
                },
              ],
            };
          }
          if (call.method == 'getOobReusableFunction') {
            return <String, dynamic>{
              'function_id': 'duplicate_type',
              'parameters': <Map<String, dynamic>>[
                <String, dynamic>{
                  'name': 'wrong_text',
                  'default': 'x',
                  'bindings': <String>[r'$.execution.steps[0].args.text'],
                },
                <String, dynamic>{
                  'name': 'kept_text',
                  'default': 'y',
                  'bindings': <String>[r'$.execution.steps[1].args.text'],
                },
              ],
              'actions': <Map<String, dynamic>>[
                <String, dynamic>{'type': 'input_text', 'text': 'x'},
                <String, dynamic>{'type': 'input_text', 'text': 'y'},
              ],
              'execution': <String, dynamic>{
                'steps': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'step_1',
                    'index': 0,
                    'title': '错误输入',
                    'executor': 'omniflow',
                    'tool': 'input_text',
                    'args': <String, dynamic>{'text': 'x'},
                  },
                  <String, dynamic>{
                    'id': 'step_2',
                    'index': 1,
                    'title': '有效输入',
                    'executor': 'omniflow',
                    'tool': 'input_text',
                    'args': <String, dynamic>{'text': 'y'},
                  },
                ],
              },
            };
          }
          if (call.method == 'registerOobReusableFunction') {
            final arguments = Map<String, dynamic>.from(call.arguments as Map);
            savedSpec = Map<String, dynamic>.from(
              arguments['functionSpec'] as Map,
            );
            return <String, dynamic>{
              'success': true,
              'function_id': 'duplicate_type',
              'created_function_id': 'duplicate_type',
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
    await tester.tap(find.byIcon(Icons.info_outline_rounded));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.delete_outline).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除').last);
    await tester.pumpAndSettle();

    final execution = savedSpec?['execution'] as Map;
    final steps = execution['steps'] as List;
    expect(steps, hasLength(1));
    expect((steps.single as Map)['id'], 'step_1');
    expect((steps.single as Map)['title'], '有效输入');
    expect(((savedSpec?['actions'] as List).single as Map)['text'], 'y');
    final parameters = savedSpec?['parameters'] as List;
    expect(parameters, hasLength(1));
    expect((parameters.single as Map)['name'], 'kept_text');
    expect((parameters.single as Map)['bindings'], <String>[
      r'$.execution.steps[0].args.text',
    ]);
  });

  testWidgets(
    'Reusable command run button invokes local execution and shows running state',
    (tester) async {
      final runCompleter = Completer<Map<String, dynamic>>();
      final methodCalls = <MethodCall>[];
      var runCalls = 0;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            methodCalls.add(call);
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
            if (call.method == 'getOobReusableFunction') {
              return <String, dynamic>{
                'success': true,
                'function_id': 'open_settings',
                'parameters': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'name': 'package_name',
                    'type': 'string',
                    'required': true,
                    'default': 'com.android.settings',
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
          home: CommandLibraryPage(),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('执行'));
      await tester.pump();

      expect(runCalls, 1);
      expect(find.text('执行中'), findsOneWidget);
      final runCall = methodCalls.singleWhere(
        (call) => call.method == 'runOobReusableFunction',
      );
      expect(
        Map<String, dynamic>.from(runCall.arguments as Map)['functionId'],
        'open_settings',
      );
      expect(
        Map<String, dynamic>.from(
          Map<String, dynamic>.from(runCall.arguments as Map)['arguments']
              as Map,
        ),
        containsPair('package_name', 'com.android.settings'),
      );

      runCompleter.complete(<String, dynamic>{
        'success': true,
        'function_id': 'open_settings',
        'goal': 'oob_reusable_function_run:open_settings',
        'timing': <String, dynamic>{
          'started_at_ms': 1700000000000,
          'finished_at_ms': 1700000002450,
          'runner_duration_ms': 2450,
          'phase_ms': <String, dynamic>{
            'parse_request_ms': 3,
            'read_current_package_ms': 4,
            'read_current_page_ms': 5,
            'page_match_ms': 6,
            'rank_functions_ms': 7,
            'segment_match_ms': 8,
          },
        },
        'execution_status': 'completed_local',
        'terminal_state': <String, dynamic>{
          'status': 'completed_local',
          'execution_status': 'completed_local',
        },
        'context': <String, dynamic>{
          'step_results': <Map<String, dynamic>>[
            <String, dynamic>{
              'success': true,
              'tool': 'open_app',
              'executor': 'omniflow',
              'duration_ms': 120,
              'compile_kind': 'hit',
              'compile_result': <String, dynamic>{
                'compile_status': 'hit',
                'function_id': 'open_settings',
              },
            },
          ],
        },
      });
      await tester.pumpAndSettle();

      expect(find.text('复用指令执行结果'), findsNothing);

      expect(find.text('执行'), findsOneWidget);
    },
  );

  testWidgets('Reusable command run asks for missing required arguments', (
    tester,
  ) async {
    final methodCalls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          methodCalls.add(call);
          if (call.method == 'listOobReusableFunctions') {
            return <String, dynamic>{
              'success': true,
              'count': 1,
              'functions': <Map<String, dynamic>>[
                <String, dynamic>{
                  'function_id': 'search_settings',
                  'name': '搜索设置',
                  'description': '在设置里搜索',
                  'step_count': 1,
                  'parameter_names': <String>['query'],
                  'registered_at': '1700000000000',
                  'step_summaries': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'index': 0,
                      'title': '搜索',
                      'tool': 'input_text',
                    },
                  ],
                },
              ],
            };
          }
          if (call.method == 'getOobReusableFunction') {
            return <String, dynamic>{
              'success': true,
              'function_id': 'search_settings',
              'parameters': <Map<String, dynamic>>[
                <String, dynamic>{
                  'name': 'query',
                  'type': 'string',
                  'required': true,
                  'description': '搜索词',
                },
              ],
            };
          }
          if (call.method == 'runOobReusableFunction') {
            return <String, dynamic>{
              'success': true,
              'function_id': 'search_settings',
              'execution_status': 'completed_local',
              'terminal_state': <String, dynamic>{
                'status': 'completed_local',
                'execution_status': 'completed_local',
              },
              'context': <String, dynamic>{
                'step_results': <Map<String, dynamic>>[],
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

    await tester.tap(find.text('执行'));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('填写执行参数'), findsOneWidget);
    expect(find.text('query'), findsOneWidget);
    expect(
      methodCalls.where((call) => call.method == 'runOobReusableFunction'),
      isEmpty,
    );

    await tester.enterText(find.byType(TextField), 'wifi');
    await tester.tap(find.widgetWithText(FilledButton, '执行'));
    await tester.pumpAndSettle();

    final runCall = methodCalls.singleWhere(
      (call) => call.method == 'runOobReusableFunction',
    );
    final runArgs = Map<String, dynamic>.from(runCall.arguments as Map);
    expect(runArgs['functionId'], 'search_settings');
    expect(
      Map<String, dynamic>.from(runArgs['arguments'] as Map),
      containsPair('query', 'wifi'),
    );
    expect(find.text('复用指令执行结果'), findsNothing);
  });

  testWidgets('Memory Center reusable command embed keeps OOB interactions', (
    tester,
  ) async {
    final methodCalls = <MethodCall>[];
    final runCompleter = Completer<Map<String, dynamic>>();
    var deleted = false;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          methodCalls.add(call);
          if (call.method == 'listOobReusableFunctions') {
            return <String, dynamic>{
              'success': true,
              'count': deleted ? 0 : 1,
              'functions': deleted
                  ? <Map<String, dynamic>>[]
                  : <Map<String, dynamic>>[
                      <String, dynamic>{
                        'function_id': 'open_settings',
                        'name': '打开 Settings',
                        'description': '打开 Android 设置',
                        'step_count': 1,
                        'parameter_names': <String>['package_name'],
                        'registered_at': '1700000000000',
                        'source_run_ids': <String>['run-1'],
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
          if (call.method == 'getOobReusableFunction') {
            return <String, dynamic>{
              'success': true,
              'function_id': 'open_settings',
              'name': '打开 Settings',
              'description': '打开 Android 设置',
              'parameters': <Map<String, dynamic>>[
                <String, dynamic>{
                  'name': 'package_name',
                  'type': 'string',
                  'required': false,
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
          if (call.method == 'runOobReusableFunction') {
            return runCompleter.future;
          }
          if (call.method == 'deleteOobReusableFunction') {
            deleted = true;
            return <String, dynamic>{'success': true, 'deleted': true};
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: CommandLibraryEmbed()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('打开 Settings'), findsOneWidget);
    expect(find.text('打开 Android 设置'), findsOneWidget);
    expect(find.text('类型 OmniFlow'), findsNothing);
    expect(find.text('状态 已注册'), findsNothing);
    expect(find.text('步骤 1'), findsOneWidget);
    expect(find.text('参数 1'), findsOneWidget);
    expect(find.text('RunLogs 1'), findsOneWidget);
    expect(find.text('执行'), findsOneWidget);
    expect(find.byIcon(Icons.info_outline_rounded), findsOneWidget);
    expect(find.byIcon(Icons.delete_outline_rounded), findsOneWidget);

    await tester.tap(find.text('打开 Settings'));
    await tester.pumpAndSettle();

    expect(find.text('复用指令详情'), findsNothing);
    expect(
      methodCalls.where((call) => call.method == 'getOobReusableFunction'),
      isEmpty,
    );

    await tester.tap(find.byIcon(Icons.info_outline_rounded));
    await tester.pumpAndSettle();

    expect(find.text('复用指令详情'), findsOneWidget);
    expect(
      methodCalls.where((call) => call.method == 'getOobReusableFunction'),
      hasLength(1),
    );

    Navigator.of(tester.element(find.text('复用指令详情'))).pop();
    await tester.pumpAndSettle();

    await tester.tap(find.text('执行'));
    await tester.pump();

    expect(find.text('执行中'), findsOneWidget);
    final runCall = methodCalls.singleWhere(
      (call) => call.method == 'runOobReusableFunction',
    );
    expect(
      Map<String, dynamic>.from(runCall.arguments as Map)['functionId'],
      'open_settings',
    );

    runCompleter.complete(<String, dynamic>{
      'success': true,
      'function_id': 'open_settings',
      'execution_status': 'completed_local',
      'terminal_state': <String, dynamic>{
        'status': 'completed_local',
        'execution_status': 'completed_local',
      },
      'context': <String, dynamic>{
        'step_results': <Map<String, dynamic>>[
          <String, dynamic>{
            'success': true,
            'tool': 'open_app',
            'executor': 'omniflow',
          },
        ],
      },
    });
    await tester.pumpAndSettle();

    expect(find.text('复用指令执行结果'), findsNothing);

    await tester.tap(find.byIcon(Icons.delete_outline_rounded));
    await tester.pumpAndSettle();

    expect(find.text('删除复用指令'), findsOneWidget);

    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();

    expect(
      methodCalls.where((call) => call.method == 'deleteOobReusableFunction'),
      hasLength(1),
    );
    expect(find.text('打开 Settings'), findsNothing);
    expect(find.text('暂无复用指令'), findsOneWidget);
  });
}
