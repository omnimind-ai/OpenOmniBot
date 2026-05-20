import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getInternalRunLogTimeline') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            if (args['runId'] == 'run-vlm-only') {
              return <String, dynamic>{
                'success': true,
                'run_id': 'run-vlm-only',
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
            return <String, dynamic>{
              'success': true,
              'run_id': 'run-vlm',
              'done_reason': 'finished',
              'goal': '打开 Settings',
              'cards': <Map<String, dynamic>>[
                <String, dynamic>{
                  'compile_kind': 'vlm_step',
                  'source': 'vlm',
                  'header': <String, dynamic>{
                    'step_index': 0,
                    'success': true,
                    'duration_ms': 0,
                    'compile_kind': 'vlm_step',
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
    expect(find.text('1 步'), findsOneWidget);
    expect(find.text('第 1 步'), findsOneWidget);
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
    expect(_richTextContaining('VLM 决策 / OmniFlow 本地重放'), findsNothing);
    expect(_richTextContaining('重放  OmniFlow 本地'), findsNothing);
    expect(find.textContaining('VLM'), findsNothing);
    expect(find.textContaining('Visual task'), findsNothing);
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
