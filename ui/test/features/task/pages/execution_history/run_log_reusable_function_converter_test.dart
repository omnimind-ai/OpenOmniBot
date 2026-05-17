import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/run_log/run_log_reusable_function_converter.dart';
import 'package:ui/features/task/run_log/run_log_replay_policy.dart';

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

  List<Map<String, dynamic>> stepsFrom(Map<String, dynamic> spec) {
    final execution = spec['execution'] as Map<String, dynamic>;
    return (execution['steps'] as List).cast<Map<String, dynamic>>();
  }

  Map<String, dynamic> argsFor(String toolName) {
    switch (toolName) {
      case 'click':
      case 'long_press':
        return {'x': 120, 'y': 240};
      case 'scroll':
      case 'swipe':
        return {'x1': 500, 'y1': 1600, 'x2': 500, 'y2': 800};
      case 'type':
      case 'input_text':
        return {'content': 'hello'};
      case 'open_app':
        return {'package_name': 'com.example.app'};
      case 'hot_key':
      case 'press_key':
        return {'key': 'ENTER'};
      case 'wait':
        return {'duration_ms': 1000};
      case 'finished':
        return {'content': 'done'};
      default:
        return const {};
    }
  }

  test('replay policy matches shared json contract', () {
    final file = File(
      '../app/src/main/assets/omniflow/runlog/replay_policy.json',
    );
    final policy = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;

    expect(policy['schema_version'], RunLogReplayPolicy.schemaVersion);
    expect(
      _stringSet(policy['omniflow_actions']),
      RunLogReplayPolicy.omniflowActions,
    );
    expect(
      _stringMap(policy['omniflow_action_aliases']),
      RunLogReplayPolicy.omniflowActionAliases,
    );
    expect(
      _stringSet(policy['coordinate_actions']),
      RunLogReplayPolicy.coordinateActions,
    );
    expect(
      _stringSet(policy['perception_tools']),
      RunLogReplayPolicy.perceptionTools,
    );
    expect(
      _stringSet(policy['data_flow_tools']),
      RunLogReplayPolicy.dataFlowTools,
    );
    expect(
      _stringSet(policy['provider_only_tools']),
      RunLogReplayPolicy.providerOnlyTools,
    );
    expect(_stringSet(policy['skip_tools']), RunLogReplayPolicy.skipTools);
  });

  test('marks executable VLM actions as omniflow model-free steps', () {
    const actions = [
      'click',
      'long_press',
      'scroll',
      'type',
      'input_text',
      'swipe',
      'open_app',
      'press_home',
      'press_back',
      'press_key',
      'hot_key',
      'wait',
      'finished',
    ];

    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-1',
      title: 'Replay actions',
      payload: const {'goal': 'Replay actions'},
      cards: [for (final action in actions) card(action, argsFor(action))],
      useEnglish: true,
    );

    expect(spec.containsKey('tags'), isFalse);
    expect(spec.containsKey('runtime_targets'), isFalse);
    expect(spec.containsKey('call_contract'), isFalse);
    expect(spec.containsKey('script_reuse'), isFalse);
    expect(spec.containsKey('agent_reuse'), isFalse);
    final steps = stepsFrom(spec);
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

      if (RunLogReplayPolicy.isCoordinateAction(action)) {
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

  test('normalizes Omniflow action aliases before export', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-aliases',
      title: 'Replay aliases',
      payload: const {'goal': 'Replay aliases'},
      cards: [
        card('tap', const {'x': 120, 'y': 240}),
        card('type_text', const {'text': 'hello'}),
        card('done', const {'content': 'done'}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps.map((step) => step['tool']), [
      'click',
      'input_text',
      'finished',
    ]);
    expect(steps.map((step) => step['callable_tool']), [
      'click',
      'input_text',
      'finished',
    ]);
    expect(steps.map((step) => step['source_tool']), [
      'tap',
      'type_text',
      'done',
    ]);
    expect(
      (steps.first['source_context'] as Map)['action'],
      containsPair('tool', 'click'),
    );
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

    final steps = stepsFrom(spec);
    expect(steps.single['executor'], 'agent');
    expect(steps.single['model_free'], isNot(isTrue));
    expect(steps.single['callable_tool'], 'oob.agent.run');
    expect(steps.single['agent_call'], isA<Map>());
  });

  test('keeps VLM-only runlog as an agent replay step', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-vlm-only',
      title: 'Find and tap settings',
      payload: const {'goal': 'Find and tap settings'},
      cards: [
        card('vlm_task', const {'goal': 'Find and tap settings'}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(1));
    final step = steps.single;
    expect(step['tool'], 'vlm_task');
    expect(step['executor'], 'agent');
    expect(step['callable_tool'], 'oob.agent.run');
    expect((step['tool_binding'] as Map)['kind'], 'agent_replan');

    final agentCall = step['agent_call'] as Map;
    expect(agentCall['tool'], 'oob.agent.run');
    expect(
      agentCall['reason'],
      'perception_only_step_without_recorded_actions',
    );
    expect((agentCall['args'] as Map)['original_tool'], 'vlm_task');

    final capabilities = (spec['execution'] as Map)['capabilities'] as Map;
    expect(capabilities['agent_step_count'], 1);
    expect(capabilities['omniflow_step_count'], 0);
    expect(capabilities['requires_agent_fallback'], isTrue);
  });

  test('skips VLM perception wrapper when recorded action card exists', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-vlm-click',
      title: 'Tap Open',
      payload: const {'goal': 'Tap Open'},
      cards: [
        card('vlm_task', const {'goal': 'Tap Open'}),
        card('click', const {'target_description': 'Open', 'x': 120, 'y': 240}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(1));
    final clickStep = steps.single;
    expect(clickStep['id'], 'step_1');
    expect(clickStep['index'], 0);
    expect(clickStep['source_index'], 1);
    expect(clickStep['tool'], 'click');
    expect(clickStep['executor'], 'omniflow');
    expect(clickStep['model_free'], isTrue);
    expect(clickStep['coordinate_hook'], 'omniflow');
    expect(clickStep.containsKey('agent_call'), isFalse);

    final parameters = (spec['parameters'] as List).cast<Map>();
    final targetParameter = parameters.firstWhere(
      (item) => item['name'] == 'target',
    );
    expect(
      targetParameter['bindings'],
      contains(r'$.execution.steps[0].args.target_description'),
    );
  });

  test('keeps data-flow tools on agent replan instead of direct replay', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-browser',
      title: 'Research docs',
      payload: const {'goal': 'Research docs'},
      cards: [
        card('browser_use', const {
          'url': 'https://example.com',
          'query': 'release notes',
        }),
        card('web_search', const {'query': 'OmniBot docs'}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(2));
    for (final step in steps) {
      expect(step['executor'], 'agent');
      expect(step['scriptable'], isFalse);
      expect(step['callable_tool'], 'oob.agent.run');
      expect((step['tool_binding'] as Map)['kind'], 'agent_replan');
      final agentCall = step['agent_call'] as Map;
      expect(agentCall['tool'], 'oob.agent.run');
      expect(agentCall['reason'], 'data_flow_tool_requires_live_context');
      expect((agentCall['args'] as Map)['original_tool'], step['tool']);
    }

    final parameters = (spec['parameters'] as List).cast<Map>();
    final queryParameter = parameters.firstWhere(
      (item) => item['name'] == 'query',
    );
    expect(
      queryParameter['bindings'],
      contains(r'$.execution.steps[0].agent_call.args.original_args.query'),
    );
  });

  test('keeps provider-owned graph and function tools on agent replan', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-provider-owned',
      title: 'Provider owned replay',
      payload: const {'goal': 'Provider owned replay'},
      cards: [
        card('go_to_node', const {'node_id': 'node_1'}),
        card('call_function', const {'function_id': 'func_provider'}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(2));
    for (final step in steps) {
      expect(step['executor'], 'agent');
      expect(step['scriptable'], isFalse);
      expect(step['callable_tool'], 'oob.agent.run');
      expect(
        (step['agent_call'] as Map)['reason'],
        'provider_owned_replay_requires_omniflow',
      );
    }
  });

  test(
    'AI normalization cannot turn data-flow steps into direct tool replay',
    () {
      final fallback = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-ai-browser',
        title: 'Research docs',
        payload: const {'goal': 'Research docs'},
        cards: [
          card('browser_use', const {
            'url': 'https://example.com',
            'query': 'release notes',
          }),
        ],
        useEnglish: true,
      );
      final aiJson = {
        ...fallback,
        'execution': {
          ...(fallback['execution'] as Map),
          'capabilities': {
            'scriptable_step_count': 1,
            'agent_step_count': 0,
            'requires_agent_fallback': false,
          },
          'steps': [
            {
              ...(stepsFrom(fallback).single),
              'executor': 'tool',
              'scriptable': true,
              'callable_tool': 'browser_use',
              'tool_binding': {'kind': 'oob_agent_tool', 'name': 'browser_use'},
              'agent_call': {
                'tool': 'browser_use',
                'args': {'prompt': 'Search release notes'},
              },
            },
          ],
        },
      };

      final normalized =
          RunLogReusableFunctionConverter.normalizeAiJsonForTesting(
            aiJson.cast<String, dynamic>(),
            fallback,
          );

      final step = stepsFrom(normalized).single;
      expect(step['executor'], 'agent');
      expect(step['scriptable'], isFalse);
      expect(step['callable_tool'], 'oob.agent.run');
      expect((step['tool_binding'] as Map)['kind'], 'agent_replan');
      expect((step['tool_binding'] as Map)['callable_tool'], 'oob.agent.run');
      expect(
        (step['agent_call'] as Map)['reason'],
        'data_flow_tool_requires_live_context',
      );

      final capabilities =
          (normalized['execution'] as Map)['capabilities'] as Map;
      expect(capabilities['scriptable_step_count'], 0);
      expect(capabilities['agent_step_count'], 1);
      expect(capabilities['requires_agent_fallback'], isTrue);
    },
  );
}

Set<String> _stringSet(Object? value) {
  return (value as List).map((item) => item.toString()).toSet();
}

Map<String, String> _stringMap(Object? value) {
  return (value as Map).map(
    (key, item) => MapEntry(key.toString(), item.toString()),
  );
}
