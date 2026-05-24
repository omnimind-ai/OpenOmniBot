import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  testWidgets('Function run result displays timing diagnostics', (
    tester,
  ) async {
    final result = UtgManualRunResult.fromMap(<String, dynamic>{
      'success': true,
      'goal': 'oob_reusable_function_run:open_settings',
      'function_id': 'open_settings',
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
      'terminal_state': <String, dynamic>{
        'status': 'completed',
        'runner': 'oob_omniflow_replay',
        'step_count': 1,
        'success_step_count': 1,
      },
      'context': <String, dynamic>{
        'step_results': <Map<String, dynamic>>[
          <String, dynamic>{
            'success': true,
            'tool': 'open_app',
            'executor': 'omniflow',
            'duration_ms': 120,
          },
        ],
      },
    });

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: FunctionRunResultInlinePanel(result: result),
          ),
        ),
      ),
    );

    expect(_richTextContaining('耗时  2.5s'), findsOneWidget);
    expect(_richTextContaining('开始'), findsOneWidget);
    expect(_richTextContaining('结束'), findsOneWidget);
    expect(_richTextContaining('解析请求  3ms'), findsOneWidget);
    expect(_richTextContaining('读取应用  4ms'), findsOneWidget);
    expect(_richTextContaining('读取页面  5ms'), findsOneWidget);
    expect(_richTextContaining('页面匹配  6ms'), findsOneWidget);
    expect(_richTextContaining('Function 排序  7ms'), findsOneWidget);
    expect(_richTextContaining('段命中  8ms'), findsOneWidget);
  });
}

Finder _richTextContaining(String text) {
  return find.byWidgetPredicate(
    (widget) => widget is RichText && widget.text.toPlainText().contains(text),
    description: 'RichText containing "$text"',
  );
}
