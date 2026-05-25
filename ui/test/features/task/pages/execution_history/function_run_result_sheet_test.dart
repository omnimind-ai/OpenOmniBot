import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  testWidgets('Function run result keeps timing internal and hidden from UI', (
    tester,
  ) async {
    final result = UtgManualRunResult.fromMap(<String, dynamic>{
      'success': true,
      'goal': 'oob_reusable_function_run:open_settings',
      'function_id': 'open_settings',
      'compile_kind': 'hit',
      'compile_status': 'hit',
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
            'compile_kind': 'hit',
            'compile_result': <String, dynamic>{
              'compile_status': 'hit',
              'function_id': 'open_settings',
            },
          },
        ],
      },
    });

    expect(result.durationMs, 2450);
    expect(result.startedAtMs, 1700000000000);
    expect(result.finishedAtMs, 1700000002450);
    expect(result.phaseMs['parse_request_ms'], 3);
    expect(result.phaseMs['read_current_package_ms'], 4);
    expect(result.phaseMs['read_current_page_ms'], 5);
    expect(result.phaseMs['page_match_ms'], 6);
    expect(result.phaseMs['rank_functions_ms'], 7);
    expect(result.phaseMs['segment_match_ms'], 8);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: FunctionRunResultInlinePanel(
              result: result,
              showRawJson: true,
            ),
          ),
        ),
      ),
    );

    expect(find.text('时间统计'), findsNothing);
    expect(_selectableTextContaining('duration_ms'), findsNothing);
    expect(_selectableTextContaining('2450ms'), findsNothing);
    expect(_selectableTextContaining('started_at_ms'), findsNothing);
    expect(_selectableTextContaining('finished_at_ms'), findsNothing);
    expect(_selectableTextContaining('phase_ms'), findsNothing);
    expect(_selectableTextContaining('parse_request_ms'), findsNothing);
    expect(_selectableTextContaining('read_current_package_ms'), findsNothing);
    expect(_selectableTextContaining('read_current_page_ms'), findsNothing);
    expect(_selectableTextContaining('page_match_ms'), findsNothing);
    expect(_selectableTextContaining('rank_functions_ms'), findsNothing);
    expect(_selectableTextContaining('segment_match_ms'), findsNothing);
    expect(find.text('执行步骤 · 1'), findsOneWidget);
    expect(find.text('open_app'), findsNothing);
    expect(find.text('120ms'), findsNothing);
    expect(find.text('模型'), findsNothing);
    expect(find.text('Fallback'), findsNothing);

    await tester.tap(find.text('原始结果'));
    await tester.pumpAndSettle();

    expect(_selectableTextContaining('route_kind'), findsOneWidget);
    expect(_selectableTextContaining('route_status'), findsWidgets);
    expect(_selectableTextContaining('route_result'), findsOneWidget);
    expect(_selectableTextContaining('compile_kind'), findsNothing);
    expect(_selectableTextContaining('compile_status'), findsNothing);
    expect(_selectableTextContaining('compile_result'), findsNothing);
    expect(_selectableTextContaining('duration_ms'), findsNothing);
    expect(_selectableTextContaining('started_at_ms'), findsNothing);
    expect(_selectableTextContaining('phase_ms'), findsNothing);
  });
}

Finder _selectableTextContaining(String text) {
  return find.byWidgetPredicate(
    (widget) => widget is SelectableText && widget.data?.contains(text) == true,
    description: 'SelectableText containing "$text"',
  );
}
