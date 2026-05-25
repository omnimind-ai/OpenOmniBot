import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/execution/execution_models.dart';

void main() {
  test('ExecutionStep.fromRunLogStep normalizes legacy route labels', () {
    final step = ExecutionStep.fromRunLogStep(0, {
      'compile_label': 'Compile hit / 编译命中',
      'tool_call': {
        'name': 'click',
        'params': {'target_description': 'Settings'},
      },
    });

    expect(step.compileLabel, 'route hit / 路由命中');
    expect(step.routeLabel, 'route hit / 路由命中');
    expect(step.compileLabel?.toLowerCase(), isNot(contains('compile')));
    expect(step.compileLabel, isNot(contains('编译')));

    final userJson = step.toUserJson();
    expect(userJson, containsPair('route_label', 'route hit / 路由命中'));
    expect(userJson, isNot(contains('compile_label')));
  });

  test('ExecutionStep.toUserJson normalizes directly constructed labels', () {
    const step = ExecutionStep(
      index: 0,
      actionType: 'click',
      compileLabel: 'Compiled / 编译完成',
    );

    final userJson = step.toUserJson();
    expect(step.routeLabel, 'routed / 路由完成');
    expect(userJson, containsPair('route_label', 'routed / 路由完成'));
    expect(userJson.toString().toLowerCase(), isNot(contains('compile')));
    expect(userJson.toString(), isNot(contains('编译')));
  });

  test(
    'ExecutionDetail.fromRunLog parses aggregate and per-step token usage',
    () {
      final detail = ExecutionDetail.fromRunLog({
        'run_id': 'run-1',
        'goal': '打开设置',
        'duration_ms': 2400,
        'token_usage_total': 1234,
        'token_usage': {
          'prompt_tokens': 800,
          'completion_tokens': 300,
          'cached_tokens': 120,
          'reasoning_tokens': 14,
        },
        'token_usage_by_step': [
          {
            'step_index': 0,
            'token_usage': {'total_tokens': 512},
          },
        ],
        'steps': [
          {
            'tool_call': {
              'name': 'click',
              'params': {'target_description': 'Settings'},
            },
            'success': true,
            'duration_ms': 120,
            'token_usage': {
              'total_tokens': 512,
              'prompt_tokens': 400,
              'completion_tokens': 112,
            },
          },
        ],
      });

      expect(detail.tokenUsage?.totalTokens, 1234);
      expect(detail.tokenUsage?.promptTokens, 800);
      expect(detail.tokenUsage?.completionTokens, 300);
      expect(detail.tokenUsage?.cachedTokens, 120);
      expect(detail.tokenUsage?.reasoningTokens, 14);
      expect(detail.tokenUsage?.stepCount, 1);
      expect(detail.tokenUsage?.compactText, contains('1.2K'));
      expect(detail.steps.single.tokenUsage?.totalTokens, 512);
      expect(
        detail.steps.single.tokenUsage?.compactText,
        contains('P400/C112'),
      );
    },
  );
}
