import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/mixins/agent_stream_handler.dart';
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

  test('uses file paths for concise file change tool titles', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'file-1',
              'type': 'fileChange',
              'path': '/repo/lib/main.dart',
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolTitle'], 'Edit main.dart');
  });

  test('uses generic tool params for concise tool titles', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'tool-1',
              'type': 'tool',
              'toolName': 'mcp__context7__query_docs',
              'arguments': '{"query":"Riverpod provider override"}',
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolTitle'], 'query_docs: Riverpod provider override');
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

  test('marks thread active from object status payload', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'active', 'activeFlags': <dynamic>[]},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.isAiResponding, isTrue);
  });

  test('marks upstream turn started notification as processing', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/started',
        'params': {
          'threadId': 'thread-1',
          'turn': {'id': 'turn-1', 'status': 'inProgress'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(result.threadId, 'thread-1');
    expect(result.turnId, 'turn-1');
    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTaskId, 'turn-1');
  });

  test(
    'renders latest snapshot reasoning as active without explicit turn id',
    () {
      final messages = codexMessagesFromThreadResponseForTesting({
        'thread': {
          'id': 'thread-1',
          'status': {'type': 'active', 'activeFlags': <dynamic>[]},
          'turns': [
            {
              'id': 'turn-1',
              'status': 'inProgress',
              'items': [
                {
                  'id': 'user-1',
                  'type': 'userMessage',
                  'content': [
                    {'text': 'hi'},
                  ],
                },
                {
                  'id': 'reasoning-1',
                  'type': 'reasoning',
                  'summary': ['thinking'],
                  'content': <dynamic>[],
                },
              ],
            },
          ],
        },
      }, active: true);

      final cardData = messages.first.cardData!;
      expect(cardData['type'], 'deep_thinking');
      expect(cardData['isLoading'], isTrue);
      expect(cardData['stage'], ThinkingStage.thinking.value);
      expect(cardData['isCollapsible'], isFalse);
      expect(messages.first.streamMeta?['isFinal'], isFalse);
    },
  );

  test('detects stale-normalized remote active turn shape', () {
    final looksActive = codexLatestTurnLooksExternallyActiveForTesting({
      'thread': {
        'id': 'thread-1',
        'status': {'type': 'idle'},
        'turns': [
          {
            'id': 'turn-1',
            'status': 'interrupted',
            'completedAt': null,
            'items': [
              {
                'id': 'reasoning-1',
                'type': 'reasoning',
                'summary': ['still writing'],
              },
            ],
          },
        ],
      },
    });

    expect(looksActive, isTrue);
  });

  test('marks thread idle from object status payload', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'active'},
          },
        },
      },
    );

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'idle'},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.isAiResponding, isFalse);
  });

  test('thread idle finalizes active turn without cancellation body', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
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
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'idle'},
          },
        },
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTaskId, isNull);
    expect(
      runtime.messages.any((message) => message.id.endsWith('cancelled')),
      isFalse,
    );
    expect(runtime.messages.single.cardData!['isLoading'], isFalse);
  });

  test('ignores replayed assistant deltas after snapshot hydration', () {
    runtime.messages.add(
      ChatMessageModel(
        id: 'msg-1-codex-agent',
        type: 1,
        user: 2,
        content: {'text': 'Hello', 'id': 'msg-1-codex-agent'},
      ),
    );

    for (final delta in const ['Hel', 'lo', '!']) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/agentMessage/delta',
            'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': delta},
          },
        },
      );
    }

    expect(runtime.messages.single.text, 'Hello!');
  });

  test('replayed assistant deltas do not restart an idle turn', () {
    runtime.messages.add(
      ChatMessageModel(
        id: 'msg-1-codex-agent',
        type: 1,
        user: 2,
        content: {'text': 'Hello', 'id': 'msg-1-codex-agent'},
      ),
    );

    for (final delta in const ['Hel', 'lo']) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/agentMessage/delta',
            'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': delta},
          },
        },
      );
    }

    expect(runtime.messages.single.text, 'Hello');
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTaskId, isNull);
    expect(runtime.currentAiMessages, isEmpty);
  });

  test('idle status does not clear partial replay delta offsets', () {
    runtime.messages.add(
      ChatMessageModel(
        id: 'msg-1-codex-agent',
        type: 1,
        user: 2,
        content: {'text': 'Hello', 'id': 'msg-1-codex-agent'},
      ),
    );

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
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'idle'},
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'lo'},
        },
      },
    );

    expect(runtime.messages.single.text, 'Hello');
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTaskId, isNull);
  });

  test('keeps replay delta offsets across matching snapshot replacement', () {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    const conversationId = 420042;
    final hydratedMessage = ChatMessageModel(
      id: 'msg-1-codex-agent',
      type: 1,
      user: 2,
      content: {'text': 'Hello', 'id': 'msg-1-codex-agent'},
    );
    final coordinatorRuntime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeCodex,
      initialMessages: [hydratedMessage],
    );
    coordinatorRuntime.codexReplayDeltaOffsets['msg-1-codex-agent'] = 3;
    coordinatorRuntime.codexReplayDeltaOffsets['stale-entry'] = 2;

    coordinator.replaceConversationSnapshot(
      conversationId: conversationId,
      mode: kChatRuntimeModeCodex,
      messages: [hydratedMessage],
    );

    final updatedRuntime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeCodex,
    )!;
    expect(updatedRuntime.codexReplayDeltaOffsets['msg-1-codex-agent'], 3);
    expect(
      updatedRuntime.codexReplayDeltaOffsets.containsKey('stale-entry'),
      isFalse,
    );
  });

  test(
    'preserves extra local duplicate user messages missing from snapshot',
    () {
      final now = DateTime.fromMillisecondsSinceEpoch(1700000000000);
      final merged = mergeRemoteCodexSnapshotMessagesForTesting(
        snapshotMessages: [
          ChatMessageModel(
            id: 'remote-user-1',
            type: 1,
            user: 1,
            content: {'text': 'again', 'id': 'remote-user-1'},
            createAt: now,
          ),
        ],
        existingMessages: [
          ChatMessageModel(
            id: 'local-user-2',
            type: 1,
            user: 1,
            content: {'text': 'again', 'id': 'local-user-2'},
            createAt: now.add(const Duration(seconds: 2)),
          ),
          ChatMessageModel(
            id: 'local-user-1',
            type: 1,
            user: 1,
            content: {'text': 'again', 'id': 'local-user-1'},
            createAt: now.add(const Duration(seconds: 1)),
          ),
        ],
        activeTaskId: null,
        isAiResponding: false,
      );

      expect(merged.map((message) => message.id), contains('remote-user-1'));
      expect(merged.map((message) => message.id), contains('local-user-2'));
      expect(
        merged.map((message) => message.id),
        isNot(contains('local-user-1')),
      );
    },
  );

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

    // item/completed for reasoning no longer flips the card to complete — the
    // turn may still emit more reasoning, tool calls, or an agent message.
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
    final midTurnCard = runtime.messages.single.cardData!;
    expect(midTurnCard['startTime'], startedStartTime);
    expect(midTurnCard['isLoading'], isTrue);
    expect(midTurnCard['stage'], ThinkingStage.thinking.value);

    // turn/completed is the terminal signal that finalizes the thinking card.
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-1'},
        },
      },
    );

    final completedCard = runtime.messages
        .firstWhere(
          (message) => message.cardData?['type'] == 'deep_thinking',
        )
        .cardData!;
    expect(completedCard['startTime'], startedStartTime);
    expect(completedCard['stage'], ThinkingStage.complete.value);
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

  test(
    'reasoning item/completed during active turn keeps thinking card loading',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'turn/started',
            'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/reasoning/textDelta',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'itemId': 'reason-1',
              'delta': 'analysing the request',
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/completed',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'itemId': 'reason-1',
              'item': {
                'id': 'reason-1',
                'type': 'reasoning',
                'summary': 'analysing the request',
              },
            },
          },
        },
      );

      final card = runtime.messages.firstWhere(
        (message) => message.cardData?['type'] == 'deep_thinking',
      );
      final cardData = card.cardData!;
      expect(cardData['isLoading'], isTrue);
      expect(cardData['isCollapsible'], isFalse);
      expect(cardData['stage'], ThinkingStage.thinking.value);
      expect(runtime.isAiResponding, isTrue);
    },
  );

  test('turn/completed finalizes the thinking card after reasoning ends', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'finished thinking',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'item': {
              'id': 'reason-1',
              'type': 'reasoning',
              'summary': 'finished thinking',
            },
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );

    final card = runtime.messages.firstWhere(
      (message) => message.cardData?['type'] == 'deep_thinking',
    );
    final cardData = card.cardData!;
    expect(cardData['isLoading'], isFalse);
    expect(cardData['isCollapsible'], isTrue);
    expect(cardData['stage'], ThinkingStage.complete.value);
    expect(runtime.isAiResponding, isFalse);
  });

  test(
    'top-level error with willRetry=false finalizes the active turn',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'turn/started',
            'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/reasoning/textDelta',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'itemId': 'reason-1',
              'delta': 'thinking',
            },
          },
        },
      );

      expect(runtime.isAiResponding, isTrue);

      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'error',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'willRetry': false,
              'message': 'connection lost',
            },
          },
        },
      );

      expect(runtime.isAiResponding, isFalse);
      expect(runtime.currentDispatchTaskId, isNull);
      final thinking = runtime.messages
          .firstWhere(
            (message) => message.cardData?['type'] == 'deep_thinking',
          )
          .cardData!;
      expect(thinking['isLoading'], isFalse);
      expect(thinking['stage'], ThinkingStage.complete.value);
    },
  );

  test('top-level error with willRetry=true keeps the turn active', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'error',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'willRetry': true,
            'message': 'rate limited',
          },
        },
      },
    );

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTaskId, isNotNull);
  });

  test(
    'snapshot renders reasoning as loading even when item.status is completed '
    'while turn is active',
    () {
      final messages = codexMessagesFromThreadResponseForTesting({
        'thread': {
          'id': 'thread-1',
          'status': {'type': 'active'},
          'turns': [
            {
              'id': 'turn-1',
              'status': 'inProgress',
              'items': [
                {
                  'id': 'reason-1',
                  'type': 'reasoning',
                  'status': 'completed',
                  'summary': ['done reasoning'],
                },
              ],
            },
          ],
        },
      }, active: true, activeTurnId: 'turn-1');

      final cardData = messages.first.cardData!;
      expect(cardData['type'], 'deep_thinking');
      expect(cardData['isLoading'], isTrue);
      expect(cardData['isCollapsible'], isFalse);
      expect(cardData['stage'], ThinkingStage.thinking.value);
    },
  );
}
