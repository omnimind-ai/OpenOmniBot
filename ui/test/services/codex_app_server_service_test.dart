import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/codex_app_server_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('startTurn forwards codex permission payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startTurn(
      conversationId: 42,
      threadId: 'thread-1',
      text: 'hello',
      approvalPolicy: 'never',
      approvalsReviewer: 'user',
      sandboxPolicy: const <String, dynamic>{'type': 'dangerFullAccess'},
      model: 'gpt-5-codex',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'turn/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['text'], 'hello');
    expect(args['approvalPolicy'], 'never');
    expect(args['approvalsReviewer'], 'user');
    expect(args['sandboxPolicy'], const <String, dynamic>{
      'type': 'dangerFullAccess',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['collaborationMode'], 'plan');
  });

  test('startReview forwards codex review payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startReview(
      conversationId: 42,
      threadId: 'thread-1',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'guardian_subagent',
      model: 'gpt-5-codex',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'review/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['approvalPolicy'], 'on-request');
    expect(args['approvalsReviewer'], 'guardian_subagent');
    expect(args['target'], const <String, dynamic>{
      'type': 'uncommittedChanges',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['collaborationMode'], 'plan');
  });

  test('lists codex models and collaboration modes', () async {
    final methods = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.listModels();
    await CodexAppServerService.listCollaborationModes();

    expect(methods, ['model/list', 'collaborationMode/list']);
  });
}
