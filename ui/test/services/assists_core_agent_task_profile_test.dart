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

  test('createAgentTask forwards tool profile and allowed tools', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          calls.add(call);
          expect(call.method, 'createAgentTask');
          return 'SUCCESS';
        });

    final success = await AssistsMessageService.createAgentTask(
      taskId: 'agent-task-1',
      userMessage: '帮我把上一条 runlog 注册了',
      toolProfile: ' function_management ',
      allowedTools: const [' oob_run_log_list ', '', 'oob_run_log_convert'],
    );

    expect(success, isTrue);
    expect(calls, hasLength(1));
    final arguments = Map<String, dynamic>.from(calls.single.arguments as Map);
    expect(arguments['toolProfile'], 'function_management');
    expect(arguments['allowedTools'], [
      'oob_run_log_list',
      'oob_run_log_convert',
    ]);
  });
}
