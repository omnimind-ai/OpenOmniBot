import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_reusable_function_converter.dart';

void main() {
  const sourceXml =
      '<hierarchy bounds="[0,0][1080,2400]">'
      '<node bounds="[100,200][300,280]" clickable="true" text="Open"/>'
      '</hierarchy>';

  Map<String, dynamic> card(String toolName, Map<String, dynamic> args) {
    return {
      'tool_name': toolName,
      'args': args,
      'before': {
        'package_name': 'com.example.app',
        'observation_xml': sourceXml,
      },
    };
  }

  Map<String, dynamic> argsFor(String toolName) {
    switch (toolName) {
      case 'click':
      case 'long_press':
        return {'x': 120, 'y': 240};
      case 'scroll':
        return {'x1': 500, 'y1': 1600, 'x2': 500, 'y2': 800};
      case 'type':
        return {'content': 'hello'};
      case 'open_app':
        return {'package_name': 'com.example.app'};
      case 'hot_key':
        return {'key': 'ENTER'};
      case 'wait':
        return {'duration_ms': 1000};
      default:
        return const {};
    }
  }

  test('marks executable VLM actions as omniflow model-free steps', () {
    const actions = [
      'click',
      'long_press',
      'scroll',
      'type',
      'open_app',
      'press_home',
      'press_back',
      'hot_key',
      'wait',
    ];

    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-1',
      title: 'Replay actions',
      payload: const {'goal': 'Replay actions'},
      cards: [for (final action in actions) card(action, argsFor(action))],
      useEnglish: true,
    );

    expect(spec['tags'], isNot(contains('omniflow')));
    final execution = spec['execution'] as Map<String, dynamic>;
    final steps = (execution['steps'] as List).cast<Map<String, dynamic>>();
    expect(steps, hasLength(actions.length));

    for (var index = 0; index < actions.length; index++) {
      final action = actions[index];
      final step = steps[index];
      expect(step['tool'], action);
      expect(step['executor'], 'omniflow');
      expect(step['model_free'], isTrue);
      expect(step['omniflow_action'], action);
      expect(step['callable_tool'], action);
      expect(step.containsKey('agent_call'), isFalse);
      expect((step['tool_binding'] as Map)['kind'], 'omniflow_action');

      if (const {'click', 'long_press', 'scroll'}.contains(action)) {
        expect(step['coordinate_hook'], 'omniflow');
        expect(
          ((step['source_context'] as Map)['src_ctx'] as Map)['page'],
          sourceXml,
        );
      } else {
        expect(step.containsKey('coordinate_hook'), isFalse);
      }
    }
  });

  test('keeps unknown VLM-routed actions on agent fallback', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-2',
      title: 'Fallback action',
      payload: const {'goal': 'Fallback action'},
      cards: [
        {
          ...card('unknown_state_action', const {'target': 'something'}),
          'compile_kind': 'vlm',
        },
      ],
      useEnglish: true,
    );

    final execution = spec['execution'] as Map<String, dynamic>;
    final steps = (execution['steps'] as List).cast<Map<String, dynamic>>();
    expect(steps.single['executor'], 'agent');
    expect(steps.single['model_free'], isNot(isTrue));
    expect(steps.single['callable_tool'], 'oob.agent.run');
    expect(steps.single['agent_call'], isA<Map>());
  });
}
