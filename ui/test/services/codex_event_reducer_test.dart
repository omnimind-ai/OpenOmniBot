import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/codex_event_reducer.dart';

void main() {
  late CodexEventReducer reducer;
  late ChatConversationRuntimeState runtime;

  setUp(() {
    reducer = const CodexEventReducer();
    runtime = ChatConversationRuntimeState(
      conversationId: 42,
      mode: kChatRuntimeModeCodex,
    );
  });

  tearDown(() {
    runtime.dispose();
  });

  test('maps agent message deltas into assistant text', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'delta': 'hello'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages.single.text, 'hello');
    expect(runtime.messages.single.user, 2);
  });

  test('maps reasoning deltas into deep thinking card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {'turnId': 'turn-1', 'delta': 'thinking'},
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'deep_thinking');
    expect(cardData['thinkingContent'], 'thinking');
    expect(cardData['isLoading'], isTrue);
  });

  test('maps command output deltas into terminal tool card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'command': 'ls',
            'delta': 'file.txt\n',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'terminal');
    expect(cardData['terminalOutput'], 'file.txt\n');
  });

  test('keeps agent message entries separate by codex item id', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'first'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-2', 'delta': 'second'},
        },
      },
    );

    expect(runtime.messages.map((message) => message.id).toList(), <String>[
      'msg-2-codex-agent',
      'msg-1-codex-agent',
    ]);
    expect(runtime.messages.first.streamMeta?['parentTaskId'], 'turn-1');
    expect(runtime.messages.first.streamMeta?['entryId'], 'msg-2-codex-agent');
    expect(runtime.messages.first.streamMeta?['seq'], 2);
    expect(runtime.messages.last.streamMeta?['seq'], 1);
  });

  test('finalizes assistant item without duplicating completed text', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'Hel'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'turnId': 'turn-1',
            'item': {'id': 'msg-1', 'type': 'agentMessage', 'text': 'Hello'},
          },
        },
      },
    );

    expect(runtime.messages.single.text, 'Hello');
    expect(runtime.messages.single.streamMeta?['isFinal'], isTrue);
    expect(runtime.currentAiMessages, isEmpty);
  });

  test('keeps reasoning timer stable across deltas and completion', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {'id': 'reason-1', 'type': 'reasoning'},
          },
        },
      },
    );

    final startedCard = runtime.messages.single;
    final startedStartTime = startedCard.cardData!['startTime'];

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'thinking',
          },
        },
      },
    );

    expect(runtime.messages.single.id, 'reason-1-codex-thinking');
    expect(runtime.messages.single.cardData!['startTime'], startedStartTime);
    expect(runtime.messages.single.cardData!['thinkingContent'], 'thinking');

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'turnId': 'turn-1',
            'item': {'id': 'reason-1', 'type': 'reasoning'},
          },
        },
      },
    );

    final completedCard = runtime.messages.single.cardData!;
    expect(completedCard['startTime'], startedStartTime);
    expect(completedCard['stage'], 4);
    expect(completedCard['isLoading'], isFalse);
    expect(completedCard['endTime'], isNotNull);
  });

  test('turn completion after manual interrupt leaves a cancellation body', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'thinking',
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-1'},
        },
      },
    );

    final cancelMessage = runtime.messages.firstWhere(
      (message) => message.id == 'turn-1-cancelled',
    );
    expect(cancelMessage.text, '任务已取消');
    expect(cancelMessage.streamMeta?['parentTaskId'], 'turn-1');
    expect(cancelMessage.streamMeta?['isFinal'], isTrue);
  });

  test('updates tool cards in place with stable codex stream metadata', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'command': 'ls',
            'delta': 'a\n',
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'command': 'ls',
            'delta': 'b\n',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['terminalOutput'], 'a\nb\n');
    expect(
      runtime.messages.single.streamMeta?['entryId'],
      'cmd-1-codex-command',
    );
    expect(runtime.messages.single.streamMeta?['seq'], 1);
  });

  test('maps approval requests into codex request card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 7,
          'method': 'item/commandExecution/requestApproval',
          'params': {'command': 'rm tmp.txt', 'reason': 'cleanup'},
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'codex_request');
    expect(cardData['requestKind'], 'approval');
    expect(cardData['requestId'], 7);
  });

  test('maps request user input into codex request card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'request-1',
          'method': 'item/tool/requestUserInput',
          'params': {
            'questions': [
              {'id': 'choice', 'question': 'Choose one'},
            ],
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'codex_request');
    expect(cardData['requestKind'], 'user_input');
    expect(cardData['questionId'], 'choice');
  });

  test('ignores unknown events without throwing', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'future/event',
          'params': {'raw': true},
        },
      },
    );

    expect(result.handled, isFalse);
    expect(runtime.messages, isEmpty);
  });

  test('ignores codex stderr logs without creating tool cards', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'codex/stderr',
          'params': {'message': 'startup log'},
        },
      },
    );

    expect(result.handled, isFalse);
    expect(runtime.messages, isEmpty);
  });

  test('removes stale codex stderr status cards', () {
    runtime.messages.add(
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'toolName': 'codex.status',
        'toolTitle': 'codex/stderr',
        'displayName': 'codex/stderr',
        'status': 'running',
      }, id: 'stderr-status'),
    );

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'codex/stderr',
          'params': {'message': 'startup log'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages, isEmpty);
  });
}
