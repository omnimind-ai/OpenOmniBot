import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  group('OOB reusable command execution bridge', () {
    test('passes default arguments and parses local completion', () async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            calls.add(call);
            expect(call.method, 'runOobReusableFunction');
            return <String, dynamic>{
              'success': true,
              'function_id': 'open_settings',
              'execution_status': 'completed_local',
              'terminal_state': <String, dynamic>{
                'status': 'completed_local',
                'execution_status': 'completed_local',
                'step_count': 1,
                'success_step_count': 1,
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
            };
          });

      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: 'open_settings',
        arguments: const {'package_name': 'com.android.settings'},
      );

      expect(calls, hasLength(1));
      final arguments = Map<String, dynamic>.from(
        calls.single.arguments as Map,
      );
      expect(arguments['functionId'], 'open_settings');
      expect(
        Map<String, dynamic>.from(arguments['arguments'] as Map),
        containsPair('package_name', 'com.android.settings'),
      );
      expect(result.success, isTrue);
      expect(result.completedLocal, isTrue);
      expect(result.startedAgentFallback, isFalse);
      expect(result.stepCount, 1);
      expect(result.successStepCount, 1);
    });

    test(
      'parses VLM fallback start separately from local completion',
      () async {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(assistCoreChannel, (call) async {
              return <String, dynamic>{
                'success': true,
                'function_id': 'search_settings',
                'execution_status': 'started_agent_fallback',
                'terminal_state': <String, dynamic>{
                  'status': 'started_agent_fallback',
                  'execution_status': 'started_agent_fallback',
                  'taskId': 'task-vlm-1',
                  'model_required': true,
                  'local_steps_completed': 1,
                  'agent_steps_pending': 2,
                },
                'context': <String, dynamic>{
                  'step_results': <Map<String, dynamic>>[
                    <String, dynamic>{
                      'success': true,
                      'tool': 'open_app',
                      'executor': 'omniflow',
                    },
                    <String, dynamic>{
                      'success': false,
                      'tool': 'input_text',
                      'executor': 'agent',
                      'needs_agent': true,
                      'fallback_available': true,
                    },
                  ],
                },
              };
            });

        final result = await AssistsMessageService.runOobReusableFunction(
          functionId: 'search_settings',
        );

        expect(result.success, isTrue);
        expect(result.executionStatus, 'started_agent_fallback');
        expect(result.startedAgentFallback, isTrue);
        expect(result.completedLocal, isFalse);
        expect(result.taskId, 'task-vlm-1');
        expect(result.stepResults, hasLength(2));
      },
    );

    test('parses accessibility preflight failure', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            return <String, dynamic>{
              'success': false,
              'function_id': 'tap_search',
              'execution_status': 'failed',
              'error_code': 'OOB_ACCESSIBILITY_REQUIRED',
              'error_message': '请先开启无障碍权限，复用指令才能执行点击、滑动和输入。',
              'required_permission': 'accessibility',
              'missing_permissions': <String>['accessibility'],
              'blocked_step_index': 0,
              'terminal_state': <String, dynamic>{
                'status': 'failed',
                'execution_status': 'failed',
                'step_count': 1,
                'success_step_count': 0,
              },
              'context': <String, dynamic>{
                'step_results': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'success': false,
                    'tool': 'click',
                    'executor': 'omniflow',
                    'summary': '请先开启无障碍权限，复用指令才能执行点击、滑动和输入。',
                    'error_code': 'OOB_ACCESSIBILITY_REQUIRED',
                    'required_permission': 'accessibility',
                  },
                ],
              },
            };
          });

      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: 'tap_search',
      );

      expect(result.success, isFalse);
      expect(result.failed, isTrue);
      expect(result.errorCode, 'OOB_ACCESSIBILITY_REQUIRED');
      expect(result.errorMessage, '请先开启无障碍权限，复用指令才能执行点击、滑动和输入。');
      expect(result.stepCount, 1);
      expect(result.successStepCount, 0);
      expect(result.stepResults.single['required_permission'], 'accessibility');
    });
  });
}
