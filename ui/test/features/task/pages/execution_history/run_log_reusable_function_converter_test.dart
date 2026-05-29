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

  Map<String, dynamic> card(
    String toolName,
    Map<String, dynamic> args, {
    bool? success,
    dynamic result,
  }) {
    return {
      'tool_name': toolName,
      'args': args,
      if (success != null) 'success': success,
      if (result != null) 'result': result,
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
      _stringSet(policy['omniflow_graph_tools']),
      RunLogReplayPolicy.omniflowGraphTools,
    );
    expect(
      _stringSet(policy['omniflow_function_tools']),
      RunLogReplayPolicy.omniflowFunctionTools,
    );
    expect(
      _stringSet(policy['omniflow_tool_call_tools']),
      RunLogReplayPolicy.omniflowToolCallTools,
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
      'input_text',
      'swipe',
      'open_app',
      'press_home',
      'press_back',
      'press_key',
      'hot_key',
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

  test('skips legacy wait cards during local conversion', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-wait-skip',
      title: 'Skip wait',
      payload: const {'goal': 'Skip wait'},
      cards: [
        card('click', const {'target_description': 'Open', 'x': 120, 'y': 240}),
        card('wait', const {'duration_ms': 1000}),
        card('type', const {'content': 'hello'}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps.map((step) => step['tool']), ['click', 'input_text']);
    expect(steps.last['source_tool'], 'type');
    expect(steps.map((step) => step['id']), ['step_1', 'step_2']);
    expect(
      steps.any(
        (step) => step['tool'] == 'wait' || step['source_tool'] == 'wait',
      ),
      isFalse,
    );

    final cleanup = (spec['metadata'] as Map)['oob_step_cleanup'] as Map;
    expect(cleanup['execution_rewrite_allowed'], isFalse);
    expect(cleanup['dropped_count'], 1);
    final events = (cleanup['events'] as List).cast<Map>();
    expect(events.single['source_index'], 1);
    expect(events.single['reason'], 'non_replayable_noise_tool');
  });

  test('deduplicates repeated legacy type input events', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-duplicate-type',
      title: 'Type once',
      payload: const {'goal': 'Type once'},
      cards: [
        card('type', const {
          'content': 'hello',
          'target_description': 'Search',
          'node_resource_id': 'search_box',
        }),
        card('input_text', const {
          'text': 'hello',
          'target_description': 'Search',
          'node_resource_id': 'search_box',
        }),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(1));
    expect(steps.single['tool'], 'input_text');
    expect((steps.single['args'] as Map)['content'], 'hello');
    expect(steps.single['source_indices'], [0, 1]);

    final annotation = steps.single['cleanup_annotation'] as Map;
    expect(annotation['cleanup_action'], 'merged_duplicate');
    expect(annotation['merged_source_indices'], [1]);

    final mergedSteps = (steps.single['merged_steps'] as List).cast<Map>();
    expect(mergedSteps.single['source_index'], 1);
    expect(mergedSteps.single['reason'], 'duplicate_text_input_same_target');

    final cleanup = (spec['metadata'] as Map)['oob_step_cleanup'] as Map;
    expect(cleanup['execution_rewrite_allowed'], isFalse);
    expect(cleanup['merged_count'], 1);
    expect(cleanup['annotated_step_count'], 1);
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

  test('failed replay card does not suppress VLM fallback', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-vlm-failed-click',
      title: 'Tap Open',
      payload: const {'goal': 'Tap Open'},
      cards: [
        card('vlm_task', const {'goal': 'Tap Open'}),
        card('click', const {
          'target_description': 'Open',
          'x': 120,
          'y': 240,
        }, success: false),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(1));
    final step = steps.single;
    expect(step['tool'], 'vlm_task');
    expect(step['executor'], 'agent');
    expect(
      (step['agent_call'] as Map)['reason'],
      'perception_only_step_without_recorded_actions',
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

  test('keeps graph tools and canonicalizes function calls to call_tool', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-omniflow-tools',
      title: 'OmniFlow tool replay',
      payload: const {'goal': 'OmniFlow tool replay'},
      cards: [
        card('go_to_node', const {'node_id': 'node_1'}),
        card('call_function', const {'function_id': 'func_provider'}),
      ],
      useEnglish: true,
    );

    final steps = stepsFrom(spec);
    expect(steps, hasLength(2));
    expect(steps[0]['executor'], 'omniflow');
    expect(steps[0]['kind'], 'omniflow_graph');
    expect(steps[0]['model_free'], isTrue);
    expect(steps[0]['scriptable'], isTrue);
    expect(steps[0]['callable_tool'], 'go_to_node');
    expect((steps[0]['tool_binding'] as Map)['kind'], 'omniflow_graph');
    expect(steps[0].containsKey('agent_call'), isFalse);

    expect(steps[1]['executor'], 'omniflow');
    expect(steps[1]['kind'], 'omniflow_function');
    expect(steps[1]['model_free'], isTrue);
    expect(steps[1]['scriptable'], isTrue);
    expect(steps[1]['tool'], 'call_tool');
    expect(steps[1]['callable_tool'], 'call_tool');
    expect(steps[1]['source_tool'], 'call_function');
    expect((steps[1]['tool_binding'] as Map)['kind'], 'omniflow_function');
    expect(steps[1].containsKey('agent_call'), isFalse);
  });

  test(
    'keeps generic call_tool as compact tool delegation when no function id',
    () {
      final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-call-tool',
        title: 'Call a live tool',
        payload: const {'goal': 'Call a live tool'},
        cards: [
          card('oob_tool_call', const {
            'toolName': 'vlm_task',
            'arguments': {'goal': 'Tap Settings'},
          }),
        ],
        useEnglish: true,
      );

      final step = stepsFrom(spec).single;
      expect(step['executor'], 'tool');
      expect(step['kind'], 'tool_call');
      expect(step['tool'], 'call_tool');
      expect(step['callable_tool'], 'call_tool');
      expect(step['source_tool'], 'oob_tool_call');
      expect(step.containsKey('model_free'), isFalse);
      expect((step['args'] as Map)['tool_name'], 'vlm_task');
    },
  );

  test('compacts oversized observed result in local conversion draft', () {
    final largeText = List.filled(600, '0123456789').join();
    final resultItems = [
      for (var index = 0; index < 25; index++)
        {
          'index': index,
          'text': largeText,
          'deep': {
            'a': {
              'b': {
                'c': {'d': 'too deep'},
              },
            },
          },
        },
    ];
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-large-result',
      title: 'Read page text',
      payload: const {'goal': 'Read page text'},
      cards: [
        card(
          'web_search',
          const {'query': 'large page'},
          result: {
            'page_text': largeText,
            'items': resultItems,
            for (var index = 0; index < 45; index++) 'meta_$index': index,
          },
        ),
      ],
      useEnglish: true,
    );

    final observed = stepsFrom(spec).single['observed_result'] as Map;
    final encoded = jsonEncode(observed);

    expect(encoded.length, lessThan(60000));
    expect(encoded, isNot(contains(largeText)));
    expect(observed['page_text'], contains('[truncated'));
    expect(observed['__truncated__'], isTrue);
    expect(observed['__omitted_entry_count__'], 7);
    final items = observed['items'] as List;
    expect(items, hasLength(21));
    expect((items.last as Map)['__omitted_item_count__'], 5);
    expect(
      (((items.first as Map)['deep'] as Map)['a'] as Map)['__truncated__'],
      isTrue,
    );
  });

  test('flattens android privileged local action arguments for replay', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-privileged-click',
      title: 'Tap Open through privileged action',
      payload: const {'goal': 'Tap Open'},
      cards: [
        card('android_privileged_action', const {
          'action': 'tap',
          'arguments': {'target_description': 'Open', 'x': 120, 'y': 240},
        }),
      ],
      useEnglish: true,
    );

    final step = stepsFrom(spec).single;
    expect(step['tool'], 'click');
    expect(step['omniflow_action'], 'click');
    expect(step['source_tool'], 'android_privileged_action');
    expect(step['executor'], 'omniflow');
    expect(step['coordinate_hook'], 'omniflow');
    final args = step['args'] as Map;
    expect(args['x'], 120);
    expect(args['y'], 240);
    expect(args.containsKey('action'), isFalse);
    expect(args.containsKey('arguments'), isFalse);
    expect(
      (step['source_context'] as Map)['action'],
      containsPair('tool', 'click'),
    );
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

  test('AI normalization keeps a tool-safe fallback function id', () {
    final fallback = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-safe-id',
      title: 'Replay safe id',
      payload: const {'goal': 'Replay safe id'},
      cards: [
        card('click', const {'x': 120, 'y': 240}),
      ],
      useEnglish: true,
    );
    final aiJson = {...fallback, 'function_id': '打开 微信.bad id'};

    final normalized =
        RunLogReusableFunctionConverter.normalizeAiJsonForTesting(
          aiJson.cast<String, dynamic>(),
          fallback,
        );

    expect(normalized['function_id'], fallback['function_id']);
  });

  test(
    'AI organizer prompt avoids user-facing route-building jargon',
    () async {
      final fallback = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-prompt-wording',
        title: 'Open settings',
        payload: const {'goal': 'Open settings'},
        cards: [
          card('open_app', const {'package_name': 'com.android.settings'}),
        ],
        useEnglish: true,
      );

      final englishPrompt =
          await RunLogReusableFunctionConverter.buildAiPromptAsync(
            fallback,
            useEnglish: true,
          );
      final zhPrompt = await RunLogReusableFunctionConverter.buildAiPromptAsync(
        fallback,
      );

      expect(englishPrompt, contains('trajectory organizer'));
      expect(englishPrompt.toLowerCase(), isNot(contains('compiler')));
      expect(englishPrompt, isNot(contains('reusable function JSON')));
      expect(englishPrompt, isNot(contains('reusable function name')));
      expect(zhPrompt, contains('轨迹整理器'));
      expect(zhPrompt, isNot(contains('轨迹编译器')));
      expect(zhPrompt, isNot(contains('可复用的 function JSON')));
      expect(zhPrompt, isNot(contains('可复用功能名')));
    },
  );

  test('agent prompt uses reusable command wording', () {
    final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
      runId: 'run-agent-prompt-wording',
      title: 'Open settings',
      payload: const {'goal': 'Open settings'},
      cards: [
        card('open_app', const {'package_name': 'com.android.settings'}),
      ],
      useEnglish: true,
    );

    final englishPrompt = RunLogReusableFunctionConverter.buildAgentPrompt(
      spec,
      useEnglish: true,
    );
    final zhPrompt = RunLogReusableFunctionConverter.buildAgentPrompt(spec);

    expect(englishPrompt, contains('Reusable command:'));
    expect(englishPrompt, contains('Reusable command ID:'));
    expect(englishPrompt, contains('Reusable command JSON:'));
    expect(englishPrompt, isNot(contains('Function:')));
    expect(englishPrompt, isNot(contains('Function ID:')));
    expect(englishPrompt, isNot(contains('Function JSON:')));
    expect(zhPrompt, contains('复用指令：'));
    expect(zhPrompt, contains('复用指令 ID：'));
    expect(zhPrompt, contains('复用指令 JSON:'));
    expect(zhPrompt, isNot(contains('Function:')));
    expect(zhPrompt, isNot(contains('Function ID:')));
    expect(zhPrompt, isNot(contains('Function JSON:')));
  });

  test('JSON extractor unwraps common model response envelopes', () async {
    final wrapped =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({
            'content': jsonEncode({
              'name': 'Wrapped command',
              'steps': [
                {'index': 0, 'title': 'Tap target'},
              ],
            }),
          }),
        );

    expect(wrapped?['name'], 'Wrapped command');
    expect(wrapped?['steps'], isA<List>());

    final listWrapped =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode([
            {
              'enhancement': {'description': 'Recovered from array envelope'},
            },
          ]),
        );

    expect(listWrapped?['description'], 'Recovered from array envelope');

    final openAiWrapped =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({
            'id': 'chatcmpl-test',
            'choices': [
              {
                'message': {
                  'content': jsonEncode({
                    'name': 'OpenAI wrapped command',
                    'steps': [
                      {'index': 0, 'title': 'Open app'},
                    ],
                  }),
                },
              },
            ],
          }),
        );

    expect(openAiWrapped?['name'], 'OpenAI wrapped command');

    final objectContent =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({
            'choices': [
              {
                'message': {
                  'content': {
                    'name': 'Object content command',
                    'steps': [
                      {'index': 0, 'title': 'Tap target'},
                    ],
                  },
                },
              },
            ],
          }),
        );

    expect(objectContent?['name'], 'Object content command');

    final outputWrapped =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({
            'output': [
              {
                'content': [
                  {
                    'type': 'output_text',
                    'text': jsonEncode({
                      'name': 'Responses wrapped command',
                      'steps': [
                        {'index': 0, 'title': 'Use output text'},
                      ],
                    }),
                  },
                ],
              },
            ],
          }),
        );

    expect(outputWrapped?['name'], 'Responses wrapped command');

    final parsedWrapped =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({
            'choices': [
              {
                'message': {
                  'parsed': {
                    'name': 'Parsed object command',
                    'steps': [
                      {'index': 0, 'title': 'Use parsed object'},
                    ],
                  },
                },
              },
            ],
          }),
        );

    expect(parsedWrapped?['name'], 'Parsed object command');

    final toolCallWrapped =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({
            'choices': [
              {
                'message': {
                  'tool_calls': [
                    {
                      'function': {
                        'name': 'emit_json',
                        'arguments': jsonEncode({
                          'name': 'Tool call command',
                          'steps': [
                            {'index': 0, 'title': 'Use tool args'},
                          ],
                        }),
                      },
                    },
                  ],
                },
              },
            ],
          }),
        );

    expect(toolCallWrapped?['name'], 'Tool call command');

    final doubleEncoded =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode(
            jsonEncode({
              'name': 'Double encoded command',
              'steps': [
                {'index': 0, 'title': 'Decode twice'},
              ],
            }),
          ),
        );

    expect(doubleEncoded?['name'], 'Double encoded command');

    final multiObjectText =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync('''
Example shape:
{"name":"short reusable command name","description":"one sentence","steps":[{"index":0,"title":"short action title"}]}

Actual output:
{"name":"Actual enhanced command","description":"真实增强结果","steps":[{"index":0,"title":"执行真实动作"}]}
''');

    expect(multiObjectText?['name'], 'Actual enhanced command');

    final irrelevantJson =
        await RunLogReusableFunctionConverter.extractJsonObjectAsync(
          jsonEncode({'status': 'ok', 'message': 'success'}),
        );

    expect(irrelevantJson, isNull);
  });

  test(
    'label enhancement prompt uses sample JSON and compact candidates',
    () async {
      final spec = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-enhance-prompt',
        title: 'Create contact',
        payload: const {'goal': 'Create contact'},
        cards: [
          card('click', const {
            'target_description': 'Name field',
            'x': 120,
            'y': 240,
          }),
          card('type', const {'content': '妈妈'}),
        ],
        useEnglish: true,
      );

      final prompt =
          await RunLogReusableFunctionConverter.buildLabelEnhancementPromptAsync(
            spec,
            useEnglish: true,
          );

      expect(prompt, contains('Use this example shape'));
      expect(prompt, contains('OmniFlow Function Enhancer skill contract'));
      expect(prompt, contains('candidate_bindings'));
      expect(prompt, contains('cleanup_action'));
      expect(prompt, contains(r'$.execution.steps[1].args.content'));
      expect(prompt, contains('Work one section at a time'));
      expect(prompt, contains('enhanced, unchanged, partial, or failed'));
      expect(prompt, isNot(contains(sourceXml)));
      expect(prompt, isNot(contains('source_context')));
      expect(prompt, isNot(contains('Reusable command JSON:')));
    },
  );

  test(
    'agent enhancement adds safe runtime slots and reuse metadata',
    () async {
      final fallback = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-contact',
        title: 'Create contact',
        payload: const {'goal': 'Create a contact'},
        cards: [
          card('type', const {
            'content': '妈妈',
            'target_description': 'Name field',
          }),
          card('input_text', const {
            'text': '13800138000',
            'target_description': 'Phone field',
          }),
        ],
        useEnglish: true,
      );

      final enhanced =
          await RunLogReusableFunctionConverter.applyLabelEnhancementAsync({
            'name': 'Create contact',
            'description': 'Create or update a contact with runtime fields.',
            'parameters': [
              {
                'name': 'contact_name',
                'type': 'string',
                'description': 'Contact name to enter at runtime',
                'default': '妈妈',
                'bindings': [r'$.execution.steps[0].args.content'],
              },
              {
                'name': 'phone_number',
                'type': 'string',
                'description': 'Phone number to enter at runtime',
                'default': '13800138000',
                'bindings': [r'$.execution.steps[1].args.text'],
              },
            ],
            'steps': [
              {
                'index': 0,
                'title': 'Enter contact name',
                'description': 'Fill the contact name field.',
              },
              {
                'index': 1,
                'title': 'Enter phone number',
                'description': 'Fill the phone field.',
              },
            ],
            'agent_reuse': {
              'reuse_when': ['The current page shows the same contact fields.'],
              'avoid_when': ['The target page is not a contact editor.'],
              'success_signal': 'Saved contact details are visible.',
              'key_actions': [
                {
                  'step_index': 0,
                  'reason': 'Binds the runtime contact name.',
                  'parameter_names': ['contact_name'],
                },
              ],
              'segments': [
                {
                  'name': 'Fill contact fields',
                  'start_step_index': 0,
                  'end_step_index': 1,
                  'description': 'A contiguous slice for future split.',
                  'inputs': ['contact_name', 'phone_number'],
                },
              ],
            },
          }, fallback);

      final steps = stepsFrom(enhanced);
      expect((steps[0]['args'] as Map)['content'], '妈妈');
      expect((steps[1]['args'] as Map)['text'], '13800138000');

      final parameters = (enhanced['parameters'] as List).cast<Map>();
      final contact = parameters.firstWhere(
        (item) => item['name'] == 'contact_name',
      );
      final phone = parameters.firstWhere(
        (item) => item['name'] == 'phone_number',
      );
      expect(contact['default'], '妈妈');
      expect(
        contact['bindings'],
        contains(r'$.execution.steps[0].args.content'),
      );
      expect(phone['default'], '13800138000');
      expect(phone['bindings'], contains(r'$.execution.steps[1].args.text'));

      final reuse = enhanced['agent_reuse'] as Map;
      expect(reuse['mode'], 'metadata_only');
      expect(reuse['execution_rewrite_allowed'], isFalse);
      final keyActions = (reuse['key_actions'] as List).cast<Map>();
      expect(keyActions.single['step_id'], steps[0]['id']);
      expect(keyActions.single['parameter_names'], ['contact_name']);
      final segments = (reuse['segments'] as List).cast<Map>();
      expect(segments.single['materialization'], 'metadata_only');
      expect(segments.single['step_ids'], [steps[0]['id'], steps[1]['id']]);
      expect(segments.single['input_parameters'], [
        'contact_name',
        'phone_number',
      ]);
    },
  );

  test(
    'agent enhancement annotates cleanup candidates without rewriting execution',
    () async {
      final fallback = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-cleanup-annotation',
        title: 'Tap duplicate control',
        payload: const {'goal': 'Tap duplicate control'},
        cards: [
          card('click', const {
            'target_description': 'Open',
            'x': 120,
            'y': 240,
          }),
        ],
        useEnglish: true,
      );
      final beforeStep = stepsFrom(fallback).single;

      final enhanced =
          await RunLogReusableFunctionConverter.applyLabelEnhancementAsync({
            'steps': [
              {
                'index': 0,
                'title': 'Tap duplicate button',
                'description': 'Duplicate tap after the target was selected.',
                'importance': 'noise',
                'cleanup_action': 'drop',
                'cleanup_reason':
                    'Repeated tap after the same target was already clicked.',
              },
            ],
            'execution': {
              'steps': [
                {
                  'tool': 'shell_exec',
                  'executor': 'agent',
                  'args': {'cmd': 'rm -rf /'},
                },
              ],
            },
          }, fallback);

      final afterStep = stepsFrom(enhanced).single;
      expect(afterStep['tool'], beforeStep['tool']);
      expect(afterStep['executor'], beforeStep['executor']);
      expect(afterStep['args'], beforeStep['args']);
      expect(afterStep['title'], 'Tap duplicate button');
      expect(
        afterStep['description'],
        'Duplicate tap after the target was selected.',
      );

      final annotation = afterStep['cleanup_annotation'] as Map;
      expect(annotation['source'], 'run_log_agent_label_enhancer');
      expect(annotation['cleanup_action'], 'drop_candidate');
      expect(annotation['importance'], 'noise');
      expect(annotation['execution_rewrite_allowed'], isFalse);
      expect(
        annotation['reason'],
        'Repeated tap after the same target was already clicked.',
      );

      final cleanup = (enhanced['metadata'] as Map)['oob_step_cleanup'] as Map;
      expect(cleanup['execution_rewrite_allowed'], isFalse);
      expect(cleanup['annotated_step_count'], 1);
      final annotatedSteps = (cleanup['annotated_steps'] as List).cast<Map>();
      expect(annotatedSteps.single['index'], 0);
      expect(
        (annotatedSteps.single['annotation'] as Map)['cleanup_action'],
        'drop_candidate',
      );
    },
  );

  test(
    'agent enhancement ignores unsafe paths and execution rewrites',
    () async {
      final fallback = RunLogReusableFunctionConverter.buildLocalFunctionJson(
        runId: 'run-unsafe-enhancement',
        title: 'Tap and type',
        payload: const {'goal': 'Tap and type'},
        cards: [
          card('click', const {
            'target_description': 'Name',
            'x': 120,
            'y': 240,
          }),
          card('input_text', const {'text': 'hello'}),
        ],
        useEnglish: true,
      );

      final enhanced =
          await RunLogReusableFunctionConverter.applyLabelEnhancementAsync({
            'parameters': [
              {
                'name': 'tap_x',
                'type': 'number',
                'default': 120,
                'bindings': [r'$.execution.steps[0].args.x'],
              },
              {
                'name': 'missing_value',
                'type': 'string',
                'bindings': [r'$.execution.steps[99].args.text'],
              },
            ],
            'steps': [
              {
                'index': 0,
                'title': 'Unsafe label only',
                'tool': 'shell_exec',
                'args': {'cmd': 'rm -rf /'},
              },
            ],
            'execution': {
              'steps': [
                {
                  'tool': 'shell_exec',
                  'args': {'cmd': 'rm -rf /'},
                },
              ],
            },
            'agent_reuse': {
              'key_actions': [
                {'step_index': 99, 'reason': 'invalid'},
              ],
              'segments': [
                {
                  'name': 'bad slice',
                  'start_step_index': 1,
                  'end_step_index': 0,
                },
              ],
            },
          }, fallback);

      final steps = stepsFrom(enhanced);
      expect(steps.first['tool'], 'click');
      expect((steps.first['args'] as Map)['x'], 120);
      expect((steps.first['args'] as Map).containsKey('cmd'), isFalse);

      final parameterNames = (enhanced['parameters'] as List)
          .cast<Map>()
          .map((item) => item['name'])
          .toSet();
      expect(parameterNames.contains('tap_x'), isFalse);
      expect(parameterNames.contains('missing_value'), isFalse);
      expect(enhanced.containsKey('agent_reuse'), isFalse);
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
