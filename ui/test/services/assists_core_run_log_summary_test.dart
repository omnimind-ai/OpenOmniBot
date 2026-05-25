import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  test(
    'UtgRunLogSummary exposes execution fields while reading legacy payloads',
    () {
      final summary = UtgRunLogSummary.fromMap(<String, dynamic>{
        'run_id': 'run-1',
        'goal': '打开设置',
        'success': true,
        'compile_status': 'hit',
        'compile_function_id': 'fn_open_settings',
        'compile_mode': 'local',
        'compile_summary': 'Compile hit / 编译命中',
      });

      expect(summary.executionStatus, 'hit');
      expect(summary.executionFunctionId, 'fn_open_settings');
      expect(summary.executionMode, 'local');
      expect(summary.executionSummary, 'execute hit / 执行命中');
    },
  );

  test('UtgRunLogSummary prefers execution fields when both names exist', () {
    final summary = UtgRunLogSummary.fromMap(<String, dynamic>{
      'run_id': 'run-2',
      'goal': '打开设置',
      'success': true,
      'execution_status': 'execution_hit',
      'compile_status': 'legacy_hit',
      'execution_function_id': 'fn_new',
      'compile_function_id': 'fn_old',
      'execution_mode': 'offline',
      'compile_mode': 'legacy',
      'execution_summary': 'reuse hit',
      'compile_summary': 'legacy summary',
    });

    expect(summary.executionStatus, 'execution_hit');
    expect(summary.executionFunctionId, 'fn_new');
    expect(summary.executionMode, 'offline');
    expect(summary.executionSummary, 'reuse hit');
  });
}
