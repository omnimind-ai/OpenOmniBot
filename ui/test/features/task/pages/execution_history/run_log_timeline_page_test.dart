import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/run_log_function_enhancement_job_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  var clipboardText = '';

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await RunLogFunctionEnhancementJobService.clearForTesting();
    clipboardText = '';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'Clipboard.setData') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            clipboardText = (args['text'] ?? '').toString();
            return null;
          }
          if (call.method == 'Clipboard.getData') {
            return <String, dynamic>{'text': clipboardText};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogTimeline') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            if (args['runId'] == 'run-vlm-only') {
              return <String, dynamic>{
                'success': true,
                'run_id': 'run-vlm-only',
                'run_finished': true,
                'run_success': true,
                'run_status': 'success',
                'done_reason': 'finished',
                'goal': '识别当前屏幕',
                'cards': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'compile_kind': 'vlm_step',
                    'source': 'vlm',
                    'header': <String, dynamic>{
                      'step_index': 0,
                      'success': true,
                      'duration_ms': 12,
                      'compile_kind': 'vlm_step',
                    },
                    'tool_call': <String, dynamic>{
                      'id': 'run-vlm-only-1',
                      'name': 'vlm_task',
                      'arguments': <String, dynamic>{'goal': '识别当前屏幕状态'},
                    },
                    'result': <String, dynamic>{'summary': '屏幕已识别'},
                  },
                ],
              };
            }
            if (args['runId'] == 'run-agent-with-nested-vlm') {
              return _nestedVlmRunLogTimelinePayload();
            }
            return <String, dynamic>{
              'success': true,
              'run_id': 'run-vlm',
              'run_finished': true,
              'run_success': true,
              'run_status': 'success',
              'done_reason': 'finished',
              'goal': '打开 Settings',
              'token_usage': <String, dynamic>{
                'prompt_tokens': 1000,
                'completion_tokens': 234,
                'total_tokens': 1234,
                'step_count': 1,
                'call_count': 2,
              },
              'token_usage_total': 1234,
              'token_usage_by_step': <Map<String, dynamic>>[
                <String, dynamic>{
                  'step_index': 0,
                  'card_id': 'run-vlm-1',
                  'tool_name': 'open_app',
                  'token_usage': <String, dynamic>{
                    'prompt_tokens': 1000,
                    'completion_tokens': 234,
                    'total_tokens': 1234,
                  },
                },
              ],
              'token_usage_by_call': <Map<String, dynamic>>[
                <String, dynamic>{
                  'call_index': 0,
                  'step_index': 0,
                  'card_id': 'run-vlm-1',
                  'tool_name': 'open_app',
                  'attempt_index': 1,
                  'token_usage': <String, dynamic>{
                    'prompt_tokens': 600,
                    'completion_tokens': 100,
                    'total_tokens': 700,
                  },
                },
                <String, dynamic>{
                  'call_index': 1,
                  'step_index': 0,
                  'card_id': 'run-vlm-1',
                  'tool_name': 'open_app',
                  'attempt_index': 2,
                  'token_usage': <String, dynamic>{
                    'prompt_tokens': 400,
                    'completion_tokens': 134,
                    'total_tokens': 534,
                  },
                },
              ],
              'cards': <Map<String, dynamic>>[
                <String, dynamic>{
                  'card_id': 'run-vlm-1',
                  'compile_kind': 'vlm_step',
                  'source': 'omniflow_replay',
                  'header': <String, dynamic>{
                    'step_index': 0,
                    'success': true,
                    'duration_ms': 0,
                    'compile_kind': 'vlm_step',
                    'token_usage_total': 1234,
                    'token_usage': <String, dynamic>{
                      'prompt_tokens': 1000,
                      'completion_tokens': 234,
                      'total_tokens': 1234,
                    },
                  },
                  'token_usage': <String, dynamic>{
                    'prompt_tokens': 1000,
                    'completion_tokens': 234,
                    'total_tokens': 1234,
                  },
                  'tool_call': <String, dynamic>{
                    'id': 'run-vlm-1',
                    'name': 'open_app',
                    'arguments': <String, dynamic>{
                      'package_name': 'com.android.settings',
                      'goal': '目标应用已打开',
                    },
                  },
                  'result': <String, dynamic>{'message': '目标应用已打开'},
                  'compile_result': <String, dynamic>{
                    'compile_status': 'hit',
                    'function_id': 'fn_open_settings',
                  },
                  'before': <String, dynamic>{
                    'package_name': 'com.android.settings',
                  },
                },
              ],
            };
          }
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('VLM OmniFlow runlog localizes fixed labels and opens detail', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('执行步骤'), findsOneWidget);
    expect(find.text('执行已完成'), findsOneWidget);
    expect(find.text('离线复用流程'), findsNothing);
    expect(find.text('RunLog 已收集'), findsNothing);
    expect(find.text('重放 RunLog'), findsNothing);
    expect(find.text('保存复用指令'), findsNothing);
    expect(find.byKey(const ValueKey('run-log-action-replay')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('run-log-action-save-function')),
      findsOneWidget,
    );
    expect(find.text('已完成 · 1 步'), findsOneWidget);
    expect(find.text('在线 VLM 成本'), findsNothing);
    expect(_richTextContaining('VLM Token  1.23k'), findsOneWidget);
    expect(_richTextContaining('VLM 调用  2'), findsNothing);
    expect(_richTextContaining('步骤  1'), findsAtLeastNWidgets(1));
    expect(_richTextContaining('输入  1.00k'), findsNothing);
    expect(_richTextContaining('输出  234'), findsNothing);
    expect(find.text('第 1 步'), findsOneWidget);
    expect(find.text('1.23k'), findsOneWidget);
    expect(find.text('OmniFlow'), findsOneWidget);
    expect(find.text('VLM'), findsNothing);
    final omniFlowBadge = tester.widget<Text>(find.text('OmniFlow').first);
    expect(omniFlowBadge.style?.color, const Color(0xFF0F9F8F));
    expect(find.textContaining('提示:'), findsOneWidget);
    expect(find.textContaining('Prompt:'), findsNothing);
    expect(find.textContaining('Step 1'), findsNothing);

    await tester.tap(find.text('打开应用 com.android.settings'));
    await tester.pumpAndSettle();

    expect(find.textContaining('OmniFlow 执行记录 · 第 1 步'), findsOneWidget);
    expect(find.text('OmniFlow 动作'), findsOneWidget);
    expect(_richTextContaining('执行方式  OmniFlow'), findsOneWidget);
    expect(_richTextContaining('Token  1.23k · P1000/C234'), findsOneWidget);
    expect(_richTextContaining('VLM 决策 / OmniFlow 本地重放'), findsNothing);
    expect(_richTextContaining('重放  OmniFlow 本地'), findsNothing);
    expect(find.textContaining('Visual task'), findsNothing);

    await tester.scrollUntilVisible(
      find.text('执行信息'),
      300,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.text('执行信息'));
    await tester.pumpAndSettle();
    expect(_selectableTextContaining('execution_status'), findsOneWidget);
    expect(_selectableTextContaining('compile_status'), findsNothing);

    await tester.scrollUntilVisible(
      find.text('原始 JSON'),
      300,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.text('原始 JSON'));
    await tester.pumpAndSettle();
    expect(_selectableTextContaining('execution_kind'), findsOneWidget);
    expect(_selectableTextContaining('execution_result'), findsOneWidget);
    expect(_selectableTextContaining('compile_kind'), findsNothing);
    expect(_selectableTextContaining('compile_result'), findsNothing);
    expect(_selectableTextContaining('route_kind'), findsNothing);
    expect(_selectableTextContaining('route_result'), findsNothing);
  });

  testWidgets('VLM-only runlog uses a single red VLM source badge', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm-only', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('VLM'), findsOneWidget);
    expect(find.text('OmniFlow'), findsNothing);
    final vlmBadge = tester.widget<Text>(find.text('VLM').first);
    expect(vlmBadge.style?.color, const Color(0xFFDB2777));

    await tester.tap(find.textContaining('视觉执行'));
    await tester.pumpAndSettle();

    expect(find.textContaining('VLM 执行记录 · 第 1 步'), findsOneWidget);
    expect(find.text('VLM 动作'), findsOneWidget);
    expect(_richTextContaining('执行方式  VLM'), findsOneWidget);
    expect(_richTextContaining('OmniFlow'), findsNothing);
  });

  testWidgets('RunLog separates execution steps from tool call detail', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogTimeline') {
            return <String, dynamic>{
              'success': true,
              'run_id': 'run-tool',
              'run_finished': true,
              'run_success': true,
              'run_status': 'success',
              'goal': '查询天气',
              'cards': <Map<String, dynamic>>[
                <String, dynamic>{
                  'card_id': 'tool-1',
                  'compile_kind': 'hit',
                  'summary': '查询天气',
                  'header': <String, dynamic>{
                    'step_index': 0,
                    'success': true,
                    'compile_kind': 'hit',
                  },
                  'tool_call': <String, dynamic>{
                    'id': 'tool-1',
                    'name': 'web_search',
                    'arguments': <String, dynamic>{'query': '今日天气'},
                  },
                  'result': <String, dynamic>{'summary': '搜索完成'},
                },
              ],
            };
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-tool', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('执行步骤'), findsOneWidget);
    expect(find.text('工具调用历史'), findsNothing);

    await tester.tap(find.text('查询天气').last);
    await tester.pumpAndSettle();

    expect(find.textContaining('工具调用 · 第 1 步'), findsOneWidget);
    expect(_richTextContaining('执行方式  工具调用'), findsOneWidget);
    expect(find.text('工具调用历史'), findsNothing);
  });

  testWidgets('Agent runlog displays VLM task and child actions as steps', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(
          runId: 'run-agent-with-nested-vlm',
          title: '',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('已完成 · 3 步'), findsOneWidget);
    expect(find.text('第 1 步'), findsOneWidget);
    expect(find.text('第 2 步'), findsOneWidget);
    expect(find.text('第 3 步'), findsOneWidget);
    expect(find.text('视觉执行 打开 Settings'), findsOneWidget);
    expect(find.text('内部 VLM 动作'), findsNothing);
    expect(find.text('打开应用 com.android.settings'), findsOneWidget);
    expect(find.text('点击 Network & internet'), findsOneWidget);
    expect(find.text('1.2s'), findsOneWidget);
    expect(find.text('320ms'), findsOneWidget);

    await tester.tap(find.text('点击 Network & internet'));
    await tester.pumpAndSettle();

    expect(find.textContaining('VLM 执行记录 · 第 3 步'), findsOneWidget);
    expect(find.text('内部 VLM 动作'), findsNothing);
    expect(find.text('点击 Network & internet'), findsWidgets);
  });

  testWidgets('Running RunLog surfaces unfinished state and disables save', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(
              runId: 'run-running',
              finished: false,
              success: null,
            );
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-running', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('执行还在进行中'), findsOneWidget);
    expect(find.text('运行中 · 1 步'), findsOneWidget);
    expect(find.text('等待结束'), findsNothing);

    final saveButton = tester.widget<IconButton>(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    expect(saveButton.onPressed, isNull);
  });

  testWidgets('RunLog copied transcript hides internal compile schema keys', (
    tester,
  ) async {
    await Clipboard.setData(const ClipboardData(text: ''));
    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('复制全部文本'));
    await tester.pump(const Duration(milliseconds: 100));

    final clipboard = await Clipboard.getData('text/plain');
    final text = clipboard?.text ?? '';
    expect(text, contains('原始时间线数据'));
    expect(text, contains('execution_kind'));
    expect(text, contains('execution_result'));
    expect(text, contains('execution_status'));
    expect(text, isNot(contains('compile_kind')));
    expect(text, isNot(contains('compile_result')));
    expect(text, isNot(contains('compile_status')));
    expect(text, isNot(contains('compile')));
    expect(text, isNot(contains('编译')));
  });

  testWidgets(
    'RunLog local execution registers the run and executes the generated reusable command',
    (tester) async {
      final methodCalls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            methodCalls.add(call);
            if (call.method == 'getInternalRunLogTimeline') {
              return _runLogTimelinePayload(runId: 'run-vlm');
            }
            if (call.method == 'getOobReusableFunction') {
              return null;
            }
            if (call.method == 'convertInternalRunLogToOobFunction') {
              return <String, dynamic>{
                'success': true,
                'created_function_id': 'fn_from_runlog',
                'function_id': 'fn_from_runlog',
                'function_spec': <String, dynamic>{
                  'schema_version': 'oob.reusable_function.v1',
                  'function_id': 'fn_from_runlog',
                  'name': '打开 Settings',
                  'description': '打开 Android 设置',
                  'parameters': <dynamic>[],
                  'execution': <String, dynamic>{
                    'kind': 'tool_sequence',
                    'steps': <Map<String, dynamic>>[
                      <String, dynamic>{
                        'id': 'step_1',
                        'index': 0,
                        'tool': 'open_app',
                        'executor': 'omniflow',
                        'args': <String, dynamic>{
                          'package_name': 'com.android.settings',
                        },
                      },
                    ],
                  },
                },
              };
            }
            if (call.method == 'runOobReusableFunction') {
              return <String, dynamic>{
                'success': true,
                'function_id': 'fn_from_runlog',
                'goal': 'oob_reusable_function_run:fn_from_runlog',
                'execution_status': 'completed_local',
                'terminal_state': <String, dynamic>{
                  'status': 'completed_local',
                  'execution_status': 'completed_local',
                  'runner': 'oob_omniflow_replay',
                  'step_count': 1,
                  'success_step_count': 1,
                },
                'step_results': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'success': true,
                    'tool': 'open_app',
                    'executor': 'omniflow',
                    'duration_ms': 12,
                  },
                ],
              };
            }
            return null;
          });

      await tester.pumpWidget(
        _buildLocalizedApp(
          locale: const Locale('zh'),
          child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('本地执行'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('run-log-action-replay')));
      await tester.pumpAndSettle();

      final convertCall = methodCalls.singleWhere(
        (call) => call.method == 'convertInternalRunLogToOobFunction',
      );
      final convertArgs = Map<String, dynamic>.from(
        convertCall.arguments as Map,
      );
      expect(convertArgs['runId'], 'run-vlm');
      expect(convertArgs['register'], isTrue);

      final runCall = methodCalls.singleWhere(
        (call) => call.method == 'runOobReusableFunction',
      );
      final runArgs = Map<String, dynamic>.from(runCall.arguments as Map);
      expect(runArgs['functionId'], 'fn_from_runlog');
      expect(runArgs['arguments'], isA<Map>());
      expect(find.text('RunLog 重放结果'), findsNothing);
    },
  );

  testWidgets(
    'RunLog registration sheet hides internal execution keys in generated reusable command JSON',
    (tester) async {
      final methodCalls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            methodCalls.add(call);
            if (call.method == 'getInternalRunLogTimeline') {
              return _runLogTimelinePayload(runId: 'run-vlm');
            }
            if (call.method == 'convertInternalRunLogToOobFunction') {
              return <String, dynamic>{
                'success': true,
                'registered': true,
                'created_function_id': 'fn_from_runlog',
                'function_id': 'fn_from_runlog',
                'function_spec': <String, dynamic>{
                  'schema_version': 'oob.reusable_function.v1',
                  'function_id': 'fn_from_runlog',
                  'name': '打开 Settings',
                  'description': '打开 Android 设置',
                  'source': <String, dynamic>{
                    'kind': 'run_log',
                    'run_id': 'run-vlm',
                    'converter': 'native_run_log_reusable_function_builder',
                  },
                  'compile_kind': 'hit',
                  'parameters': <dynamic>[],
                  'execution': <String, dynamic>{
                    'kind': 'tool_sequence',
                    'steps': <Map<String, dynamic>>[
                      <String, dynamic>{
                        'id': 'step_1',
                        'index': 0,
                        'title': '打开 Settings',
                        'kind': 'omniflow_action',
                        'tool': 'open_app',
                        'executor': 'omniflow',
                        'compile_result': <String, dynamic>{
                          'compile_status': 'hit',
                          'function_id': 'fn_from_runlog',
                        },
                        'args': <String, dynamic>{
                          'package_name': 'com.android.settings',
                        },
                      },
                    ],
                  },
                },
              };
            }
            return null;
          });

      await tester.pumpWidget(
        _buildLocalizedApp(
          locale: const Locale('zh'),
          child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const ValueKey('run-log-action-save-function')),
      );
      await tester.pump();
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 500)),
      );

      final convertCalls = methodCalls
          .where((call) => call.method == 'convertInternalRunLogToOobFunction')
          .toList(growable: false);
      expect(convertCalls, hasLength(1));
      final convertArgs = Map<String, dynamic>.from(
        convertCalls.single.arguments as Map,
      );
      expect(convertArgs['runId'], 'run-vlm');
      expect(convertArgs['register'], isTrue);

      await _pumpUntilFound(
        tester,
        find.byKey(const ValueKey('run-log-function-status-strip')),
      );
      expect(find.text('已保存为复用指令'), findsOneWidget);
      expect(find.text('RunLog 保存结果', skipOffstage: false), findsNothing);
      expect(find.text('执行步骤 · 1', skipOffstage: false), findsNothing);

      await tester.tap(
        find.byKey(const ValueKey('run-log-function-open-detail')),
      );
      await tester.pumpAndSettle();

      await _pumpUntilFound(tester, find.text('执行步骤 · 1', skipOffstage: false));
      expect(find.text('RunLog 保存结果', skipOffstage: false), findsOneWidget);
      expect(find.text('打开 Settings', skipOffstage: false), findsWidgets);
      expect(find.text('打开 Android 设置', skipOffstage: false), findsWidgets);
      expect(find.text('步骤 1', skipOffstage: false), findsOneWidget);
      expect(find.text('参数 0', skipOffstage: false), findsOneWidget);
      expect(find.text('RunLogs 1', skipOffstage: false), findsOneWidget);
      expect(find.text('保存', skipOffstage: false), findsNothing);
      expect(find.text('执行', skipOffstage: false), findsOneWidget);
      expect(find.text('增强', skipOffstage: false), findsWidgets);
      expect(find.text('定时任务', skipOffstage: false), findsOneWidget);
      expect(find.text('复制 JSON', skipOffstage: false), findsNothing);
      expect(find.text('复制 Agent 提示', skipOffstage: false), findsNothing);
      expect(find.text('转为定时任务', skipOffstage: false), findsNothing);
      expect(find.text('API', skipOffstage: false), findsNothing);
      expect(find.text('Native', skipOffstage: false), findsNothing);
      expect(find.text('Local', skipOffstage: false), findsNothing);
      expect(find.textContaining('转换', skipOffstage: false), findsNothing);
      expect(find.text('名称', skipOffstage: false), findsOneWidget);
      expect(find.text('简介', skipOffstage: false), findsOneWidget);
      expect(find.text('动作预览', skipOffstage: false), findsNothing);
      expect(find.text('高级信息', skipOffstage: false), findsOneWidget);
      expect(find.text('复用指令 JSON', skipOffstage: false), findsNothing);

      await tester.scrollUntilVisible(
        find.text('高级信息', skipOffstage: false),
        180,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('高级信息', skipOffstage: false));
      await tester.pumpAndSettle();
      expect(find.text('复用指令 JSON', skipOffstage: false), findsOneWidget);
      expect(_selectableTextContaining('execution_kind'), findsWidgets);
      expect(_selectableTextContaining('execution_result'), findsWidgets);
      expect(_selectableTextContaining('execution_status'), findsWidgets);
      expect(_selectableTextContaining('compile_kind'), findsNothing);
      expect(_selectableTextContaining('compile_result'), findsNothing);
      expect(_selectableTextContaining('compile_status'), findsNothing);
      expect(_selectableTextContaining('route_kind'), findsNothing);
      expect(_selectableTextContaining('route_result'), findsNothing);
      expect(_selectableTextContaining('route_status'), findsNothing);
      expect(_selectableTextContaining('Function'), findsNothing);
      expect(_selectableTextContaining('function'), findsNothing);
      expect(_selectableTextContaining('参考function'), findsNothing);

      expect(find.text('保存修改', skipOffstage: false), findsNothing);
    },
  );

  testWidgets('RunLog save shows inline status before explicit details', (
    tester,
  ) async {
    final methodCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          methodCalls.add(call);
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(runId: 'run-vlm');
          }
          if (call.method == 'convertInternalRunLogToOobFunction') {
            return <String, dynamic>{
              'success': true,
              'registered': true,
              'already_exists': true,
              'created_function_id': 'fn_from_runlog',
              'function_id': 'fn_from_runlog',
              'function_spec': _runLogReusableFunctionSpec(
                name: '已有 Settings 指令',
                description: '已经保存过的 Android 设置轨迹',
              ),
            };
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );

    final convertCalls = methodCalls
        .where((call) => call.method == 'convertInternalRunLogToOobFunction')
        .toList(growable: false);
    expect(convertCalls, hasLength(1));
    final convertArgs = Map<String, dynamic>.from(
      convertCalls.single.arguments as Map,
    );
    expect(convertArgs['runId'], 'run-vlm');
    expect(convertArgs['register'], isTrue);
    expect(
      methodCalls.where((call) => call.method == 'getOobReusableFunction'),
      isEmpty,
    );

    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-status-strip')),
    );
    expect(find.text('已保存为复用指令'), findsOneWidget);
    expect(find.text('RunLog 保存结果', skipOffstage: false), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('run-log-function-open-detail')),
    );
    await tester.pumpAndSettle();
    await _pumpUntilFound(
      tester,
      find.text('已有 Settings 指令', skipOffstage: false),
    );
    expect(find.text('RunLog 保存结果', skipOffstage: false), findsOneWidget);
    expect(find.text('已经保存过的 Android 设置轨迹'), findsWidgets);
  });

  testWidgets('RunLog saved command supports explicit Agent enhancement', (
    tester,
  ) async {
    final methodCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          methodCalls.add(call);
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(runId: 'run-vlm');
          }
          if (call.method == 'getOobReusableFunction') {
            return null;
          }
          if (call.method == 'convertInternalRunLogToOobFunction') {
            return <String, dynamic>{
              'success': true,
              'registered': true,
              'created_function_id': 'fn_from_runlog',
              'function_id': 'fn_from_runlog',
              'function_spec': _runLogReusableFunctionSpec(
                name: '打开 Settings',
                description: '打开 Android 设置',
              ),
            };
          }
          if (call.method == 'postLLMChat') {
            return '''
{
  "name": "打开系统设置",
  "description": "打开 Android 系统设置页，适合需要进入设置入口时复用。",
  "steps": [
    {
      "index": 0,
      "title": "打开设置应用",
      "description": "启动 Android 设置应用。"
    }
  ]
}
''';
          }
          if (call.method == 'registerOobReusableFunction') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            final spec = Map<String, dynamic>.from(args['functionSpec'] as Map);
            expect(spec['function_id'], 'fn_from_runlog');
            expect(spec['name'], '打开系统设置');
            return <String, dynamic>{
              'success': true,
              'function_id': 'fn_from_runlog',
              'created_function_id': 'fn_from_runlog',
              'already_exists': true,
              'asset_kind': 'oob_reusable_function',
              'asset_state': 'native_local',
            };
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );
    await tester.pumpAndSettle();
    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-enhance')),
    );
    await tester.tap(find.byKey(const ValueKey('run-log-function-enhance')));
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 1500)),
    );

    await _pumpUntilFound(tester, find.text('已增强并保存'));
    await tester.tap(
      find.byKey(const ValueKey('run-log-function-open-detail')),
    );
    await tester.pumpAndSettle();
    await _pumpUntilFound(tester, find.text('打开系统设置', skipOffstage: false));
    expect(find.text('打开系统设置', skipOffstage: false), findsWidgets);
    expect(
      find.text('打开 Android 系统设置页，适合需要进入设置入口时复用。', skipOffstage: false),
      findsWidgets,
    );
    expect(find.text('打开设置应用', skipOffstage: false), findsWidgets);
    expect(
      methodCalls.where((call) => call.method == 'postLLMChat'),
      hasLength(4),
    );
    expect(
      methodCalls.where((call) => call.method == 'registerOobReusableFunction'),
      hasLength(1),
    );
    expect(find.text('已增强并保存', skipOffstage: false), findsOneWidget);
    expect(find.text('已修改，保存后生效', skipOffstage: false), findsNothing);
  });

  testWidgets('RunLog Agent enhancement shows async progress state', (
    tester,
  ) async {
    final llmCompleter = Completer<String>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(runId: 'run-vlm');
          }
          if (call.method == 'getOobReusableFunction') {
            return null;
          }
          if (call.method == 'convertInternalRunLogToOobFunction') {
            return <String, dynamic>{
              'success': true,
              'registered': true,
              'created_function_id': 'fn_from_runlog',
              'function_id': 'fn_from_runlog',
              'function_spec': _runLogReusableFunctionSpec(
                name: '打开 Settings',
                description: '打开 Android 设置',
              ),
            };
          }
          if (call.method == 'postLLMChat') {
            return llmCompleter.future;
          }
          if (call.method == 'registerOobReusableFunction') {
            return <String, dynamic>{
              'success': true,
              'function_id': 'fn_from_runlog',
              'created_function_id': 'fn_from_runlog',
              'already_exists': true,
              'asset_kind': 'oob_reusable_function',
              'asset_state': 'native_local',
            };
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 1000)),
    );
    await tester.pumpAndSettle();

    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-enhance')),
    );
    await tester.tap(find.byKey(const ValueKey('run-log-function-enhance')));
    await tester.pump();

    expect(find.text('后台增强中', skipOffstage: false), findsWidgets);
    expect(find.text('已增强并保存', skipOffstage: false), findsNothing);

    llmCompleter.complete('''
{
  "name": "打开系统设置",
  "description": "打开 Android 系统设置页，适合需要进入设置入口时复用。",
  "steps": [
    {
      "index": 0,
      "title": "打开设置应用",
      "description": "启动 Android 设置应用。"
    }
  ]
}
''');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );
    await _pumpUntilFound(tester, find.text('已增强并保存', skipOffstage: false));
  });

  testWidgets('RunLog Agent enhancement marks unchanged explicitly', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(runId: 'run-vlm');
          }
          if (call.method == 'getOobReusableFunction') {
            return null;
          }
          if (call.method == 'convertInternalRunLogToOobFunction') {
            return <String, dynamic>{
              'success': true,
              'registered': true,
              'created_function_id': 'fn_from_runlog',
              'function_id': 'fn_from_runlog',
              'function_spec': _runLogReusableFunctionSpec(
                name: '打开 Settings',
                description: '打开 Android 设置',
              ),
            };
          }
          if (call.method == 'postLLMChat') {
            final prompt = (call.arguments as Map)['text'].toString();
            if (prompt.contains('每个 step')) {
              return '{}';
            }
            if (prompt.contains('运行时参数')) {
              return '{"parameters": []}';
            }
            if (prompt.contains('agent_reuse')) {
              return '{"agent_reuse": {}}';
            }
            return '''
{
  "name": "打开 Settings",
  "description": "打开 Android 设置"
}
''';
          }
          if (call.method == 'registerOobReusableFunction') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            final spec = Map<String, dynamic>.from(args['functionSpec'] as Map);
            final metadata = Map<String, dynamic>.from(spec['metadata'] as Map);
            final report = Map<String, dynamic>.from(
              metadata['oob_enhancement'] as Map,
            );
            expect(spec['name'], '打开 Settings');
            expect(report['status'], 'unchanged');
            expect(report['changed'], isFalse);
            return <String, dynamic>{
              'success': true,
              'function_id': 'fn_from_runlog',
              'created_function_id': 'fn_from_runlog',
              'already_exists': true,
              'asset_kind': 'oob_reusable_function',
              'asset_state': 'native_local',
            };
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );
    await tester.pumpAndSettle();
    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-enhance')),
    );
    await tester.tap(find.byKey(const ValueKey('run-log-function-enhance')));
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 1500)),
    );

    await _pumpUntilFound(tester, find.text('已检查，无需修改', skipOffstage: false));
    expect(find.text('已检查，无需修改', skipOffstage: false), findsOneWidget);
    expect(find.text('已增强并保存', skipOffstage: false), findsNothing);
  });

  testWidgets('RunLog Agent enhancement failure is explicit and retryable', (
    tester,
  ) async {
    final methodCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          methodCalls.add(call);
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(runId: 'run-vlm');
          }
          if (call.method == 'getOobReusableFunction') {
            return null;
          }
          if (call.method == 'convertInternalRunLogToOobFunction') {
            return <String, dynamic>{
              'success': true,
              'registered': true,
              'created_function_id': 'fn_from_runlog',
              'function_id': 'fn_from_runlog',
              'function_spec': _runLogReusableFunctionSpec(
                name: '打开 Settings',
                description: '打开 Android 设置',
              ),
            };
          }
          if (call.method == 'postLLMChat') {
            return '这不是 JSON，也无法修复。';
          }
          if (call.method == 'registerOobReusableFunction') {
            fail('failed enhancement must not save a Function');
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );
    await tester.pumpAndSettle();
    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-enhance')),
    );
    await tester.tap(find.byKey(const ValueKey('run-log-function-enhance')));
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 1500)),
    );

    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-enhance')),
    );
    expect(find.text('已增强并保存', skipOffstage: false), findsNothing);
    expect(find.text('处理失败', skipOffstage: false), findsOneWidget);
    expect(
      methodCalls.where((call) => call.method == 'registerOobReusableFunction'),
      isEmpty,
    );
    expect(
      methodCalls.where((call) => call.method == 'postLLMChat'),
      hasLength(8),
    );
  });

  testWidgets('RunLog Agent enhancement repairs non JSON model output', (
    tester,
  ) async {
    final methodCalls = <MethodCall>[];
    var llmCallCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          methodCalls.add(call);
          if (call.method == 'getInternalRunLogTimeline') {
            return _runLogTimelinePayload(runId: 'run-vlm');
          }
          if (call.method == 'getOobReusableFunction') {
            return null;
          }
          if (call.method == 'convertInternalRunLogToOobFunction') {
            return <String, dynamic>{
              'success': true,
              'registered': true,
              'created_function_id': 'fn_from_runlog',
              'function_id': 'fn_from_runlog',
              'function_spec': _runLogReusableFunctionSpec(
                name: '打开 Settings',
                description: '打开 Android 设置',
              ),
            };
          }
          if (call.method == 'postLLMChat') {
            llmCallCount++;
            final prompt = (call.arguments as Map)['text'].toString();
            if (llmCallCount == 1) {
              return '我会增强这个轨迹，但这不是 JSON。';
            }
            if (prompt.contains('修复')) {
              return '''
{
  "name": "打开系统设置",
  "description": "打开 Android 系统设置页，适合需要进入设置入口时复用。"
}
''';
            }
            if (prompt.contains('每个 step')) {
              return '''
{
  "steps": [
    {
      "index": 0,
      "title": "打开设置应用",
      "description": "启动 Android 设置应用。"
    }
  ]
}
''';
            }
            if (prompt.contains('运行时参数')) {
              return '{"parameters": []}';
            }
            if (prompt.contains('agent_reuse')) {
              return '{"agent_reuse": {"reuse_when": ["当前页面仍是 Android 桌面或设置入口时"]}}';
            }
            return '''
{
  "name": "打开系统设置",
  "description": "打开 Android 系统设置页，适合需要进入设置入口时复用。",
  "steps": [
    {
      "index": 0,
      "title": "打开设置应用",
      "description": "启动 Android 设置应用。"
    }
  ]
}
''';
          }
          if (call.method == 'registerOobReusableFunction') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            final spec = Map<String, dynamic>.from(args['functionSpec'] as Map);
            expect(spec['function_id'], 'fn_from_runlog');
            expect(spec['name'], '打开系统设置');
            return <String, dynamic>{
              'success': true,
              'function_id': 'fn_from_runlog',
              'created_function_id': 'fn_from_runlog',
              'already_exists': true,
              'asset_kind': 'oob_reusable_function',
              'asset_state': 'native_local',
            };
          }
          return null;
        });

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('zh'),
        child: const RunLogTimelinePage(runId: 'run-vlm', title: ''),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('run-log-action-save-function')),
    );
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 500)),
    );
    await tester.pumpAndSettle();
    await _pumpUntilFound(
      tester,
      find.byKey(const ValueKey('run-log-function-enhance')),
    );
    await tester.tap(find.byKey(const ValueKey('run-log-function-enhance')));
    await tester.pump();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 1500)),
    );
    await _pumpUntilFound(tester, find.text('已增强并保存', skipOffstage: false));

    expect(
      methodCalls.where((call) => call.method == 'postLLMChat'),
      hasLength(5),
    );
    expect(
      methodCalls.where(
        (call) =>
            call.method == 'postLLMChat' &&
            (call.arguments as Map)['text'].toString().contains('修复'),
      ),
      hasLength(1),
    );
    expect(
      methodCalls.where((call) => call.method == 'registerOobReusableFunction'),
      hasLength(1),
    );
    expect(find.text('已增强并保存', skipOffstage: false), findsOneWidget);
    expect(find.textContaining('没有返回可解析 JSON'), findsNothing);
  });
}

Map<String, dynamic> _nestedVlmRunLogTimelinePayload() {
  return <String, dynamic>{
    'success': true,
    'run_id': 'run-agent-with-nested-vlm',
    'run_finished': true,
    'run_success': true,
    'run_status': 'success',
    'done_reason': 'finished',
    'goal': '打开 Settings',
    'cards': <Map<String, dynamic>>[
      <String, dynamic>{
        'card_id': 'parent-vlm',
        'compile_kind': 'agent_tool',
        'span_kind': 'vlm_task',
        'child_run_id': 'child-vlm-1',
        'tool_type': 'vlm',
        'header': <String, dynamic>{
          'step_index': 0,
          'success': true,
          'duration_ms': 50000,
          'compile_kind': 'agent_tool',
          'span_kind': 'vlm_task',
        },
        'tool_call': <String, dynamic>{
          'id': 'parent-vlm',
          'name': 'vlm_task',
          'arguments': <String, dynamic>{'goal': '打开 Settings'},
        },
        'result': <String, dynamic>{
          'summary': '视觉任务已完成',
          'taskId': 'child-vlm-1',
        },
      },
      <String, dynamic>{
        'card_id': 'child-vlm-1-open',
        'parent_card_id': 'parent-vlm',
        'child_run_id': 'child-vlm-1',
        'compile_kind': 'vlm_step',
        'span_kind': 'vlm_action',
        'source': 'vlm',
        'tool_type': 'vlm',
        'header': <String, dynamic>{
          'step_index': 0,
          'success': true,
          'duration_ms': 1200,
          'compile_kind': 'vlm_step',
          'parent_card_id': 'parent-vlm',
          'span_kind': 'vlm_action',
        },
        'tool_call': <String, dynamic>{
          'id': 'child-vlm-1-open',
          'name': 'open_app',
          'arguments': <String, dynamic>{
            'package_name': 'com.android.settings',
          },
        },
        'result': <String, dynamic>{'message': '目标应用已打开'},
      },
      <String, dynamic>{
        'card_id': 'child-vlm-1-click',
        'parent_card_id': 'parent-vlm',
        'child_run_id': 'child-vlm-1',
        'compile_kind': 'vlm_step',
        'span_kind': 'vlm_action',
        'source': 'vlm',
        'tool_type': 'vlm',
        'header': <String, dynamic>{
          'step_index': 1,
          'success': true,
          'duration_ms': 320,
          'compile_kind': 'vlm_step',
          'parent_card_id': 'parent-vlm',
          'span_kind': 'vlm_action',
        },
        'tool_call': <String, dynamic>{
          'id': 'child-vlm-1-click',
          'name': 'click',
          'arguments': <String, dynamic>{
            'target_description': 'Network & internet',
            'x': 210,
            'y': 340,
          },
        },
        'result': <String, dynamic>{'message': '点击完成'},
      },
    ],
  };
}

Map<String, dynamic> _runLogReusableFunctionSpec({
  required String name,
  required String description,
}) {
  return <String, dynamic>{
    'schema_version': 'oob.reusable_function.v1',
    'function_id': 'fn_from_runlog',
    'name': name,
    'description': description,
    'source': <String, dynamic>{
      'kind': 'run_log',
      'run_id': 'run-vlm',
      'converter': 'native_run_log_reusable_function_builder',
    },
    'parameters': <dynamic>[],
    'execution': <String, dynamic>{
      'kind': 'tool_sequence',
      'steps': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'step_1',
          'index': 0,
          'title': name,
          'kind': 'omniflow_action',
          'tool': 'open_app',
          'executor': 'omniflow',
          'args': <String, dynamic>{'package_name': 'com.android.settings'},
        },
      ],
    },
  };
}

Map<String, dynamic> _runLogTimelinePayload({
  required String runId,
  bool finished = true,
  bool? success = true,
}) {
  return <String, dynamic>{
    'success': true,
    'run_id': runId,
    'run_finished': finished,
    if (success != null) 'run_success': success,
    'run_status': !finished
        ? 'running'
        : (success == false ? 'failed' : 'success'),
    'done_reason': finished ? (success == false ? 'failed' : 'finished') : '',
    'goal': '打开 Settings',
    'token_usage': <String, dynamic>{
      'prompt_tokens': 1000,
      'completion_tokens': 234,
      'total_tokens': 1234,
      'step_count': 1,
      'call_count': 2,
    },
    'token_usage_total': 1234,
    'token_usage_by_step': <Map<String, dynamic>>[
      <String, dynamic>{
        'step_index': 0,
        'card_id': '$runId-1',
        'tool_name': 'open_app',
        'token_usage': <String, dynamic>{
          'prompt_tokens': 1000,
          'completion_tokens': 234,
          'total_tokens': 1234,
        },
      },
    ],
    'token_usage_by_call': <Map<String, dynamic>>[
      <String, dynamic>{
        'call_index': 0,
        'step_index': 0,
        'card_id': '$runId-1',
        'tool_name': 'open_app',
        'attempt_index': 1,
        'token_usage': <String, dynamic>{
          'prompt_tokens': 600,
          'completion_tokens': 100,
          'total_tokens': 700,
        },
      },
      <String, dynamic>{
        'call_index': 1,
        'step_index': 0,
        'card_id': '$runId-1',
        'tool_name': 'open_app',
        'attempt_index': 2,
        'token_usage': <String, dynamic>{
          'prompt_tokens': 400,
          'completion_tokens': 134,
          'total_tokens': 534,
        },
      },
    ],
    'cards': <Map<String, dynamic>>[
      <String, dynamic>{
        'card_id': '$runId-1',
        'compile_kind': 'vlm_step',
        'source': 'vlm',
        'header': <String, dynamic>{
          'step_index': 0,
          'success': true,
          'duration_ms': 0,
          'compile_kind': 'vlm_step',
          'token_usage_total': 1234,
          'token_usage': <String, dynamic>{
            'prompt_tokens': 1000,
            'completion_tokens': 234,
            'total_tokens': 1234,
          },
        },
        'token_usage': <String, dynamic>{
          'prompt_tokens': 1000,
          'completion_tokens': 234,
          'total_tokens': 1234,
        },
        'tool_call': <String, dynamic>{
          'id': '$runId-1',
          'name': 'open_app',
          'arguments': <String, dynamic>{
            'package_name': 'com.android.settings',
            'goal': '目标应用已打开',
          },
        },
        'result': <String, dynamic>{'message': '目标应用已打开'},
        'compile_result': <String, dynamic>{
          'compile_status': 'hit',
          'function_id': 'fn_from_runlog',
        },
        'before': <String, dynamic>{'package_name': 'com.android.settings'},
      },
    ],
  };
}

Widget _buildLocalizedApp({required Locale locale, required Widget child}) {
  return MaterialApp(
    locale: locale,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: child,
  );
}

Finder _richTextContaining(String text) {
  return find.byWidgetPredicate(
    (widget) => widget is RichText && widget.text.toPlainText().contains(text),
    description: 'RichText containing "$text"',
  );
}

Finder _selectableTextContaining(String text) {
  return find.byWidgetPredicate(
    (widget) => widget is SelectableText && widget.data?.contains(text) == true,
    description: 'SelectableText containing "$text"',
  );
}

Future<void> _pumpUntilFound(WidgetTester tester, Finder finder) async {
  for (var i = 0; i < 80; i++) {
    await tester.pump(const Duration(milliseconds: 100));
    if (finder.evaluate().isNotEmpty) {
      return;
    }
  }
  expect(finder, findsWidgets);
}
