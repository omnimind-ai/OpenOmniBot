import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_run_projection.dart';

void main() {
  test(
    'projects terminal error as final status without overwriting output',
    () {
      final messages = <ChatMessageModel>[
        _assistantMessage(
          id: 'task-1-text',
          taskId: 'task-1',
          text: '已经展示给用户的正文。',
          seq: 3,
        ),
      ];

      final projection = AgentStreamRunProjection.resolveError(
        const AgentStreamEvent(
          taskId: 'task-1',
          seq: 4,
          kind: AgentStreamEventKind.error,
          createdAtMs: 4,
          entryId: 'task-1-text',
          errorMessage: 'Agent execution failed: bytePairLength=138',
          raw: <String, dynamic>{'persistAsError': true},
        ),
        messages,
      );

      expect(projection.messageId, 'task-1-text');
      expect(projection.text, '已经展示给用户的正文。');
      expect(projection.isError, isFalse);
      expect(projection.shouldWrite, isTrue);
    },
  );

  test('projects terminal error into error bubble when no output exists', () {
    final projection = AgentStreamRunProjection.resolveError(
      const AgentStreamEvent(
        taskId: 'task-2',
        seq: 1,
        kind: AgentStreamEventKind.error,
        createdAtMs: 1,
        errorMessage: 'provider stream returned error: 503',
      ),
      const <ChatMessageModel>[],
    );

    expect(projection.messageId, 'task-2-error');
    expect(projection.text, 'provider stream returned error: 503');
    expect(projection.isError, isTrue);
  });

  test('sorts same-timestamp stream messages by stream sequence', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-3-text',
        taskId: 'task-3',
        text: '正文',
        seq: 3,
      ),
      _thinkingCard(id: 'task-3-thinking', taskId: 'task-3', seq: 1),
    ];

    final sorted = [...messages]
      ..sort(AgentStreamRunProjection.compareMessagesOldestFirst);

    expect(sorted.map((message) => message.id), [
      'task-3-thinking',
      'task-3-text',
    ]);
  });

  test('drops only empty first-round thinking placeholder on later round', () {
    final emptyFirstRound = _thinkingCard(
      id: 'task-4-thinking-1',
      taskId: 'task-4',
      seq: 1,
      thinking: '',
    );
    final nonEmptyFirstRound = _thinkingCard(
      id: 'task-4-thinking-1',
      taskId: 'task-4',
      seq: 1,
      thinking: '已有思考',
    );

    expect(
      AgentStreamRunProjection.shouldDropEmptyBaseThinkingPlaceholder(
        emptyFirstRound,
        taskId: 'task-4',
        nextThinkingEntryId: 'task-4-thinking-2',
      ),
      isTrue,
    );
    expect(
      AgentStreamRunProjection.shouldDropEmptyBaseThinkingPlaceholder(
        nonEmptyFirstRound,
        taskId: 'task-4',
        nextThinkingEntryId: 'task-4-thinking-2',
      ),
      isFalse,
    );
  });
}

ChatMessageModel _assistantMessage({
  required String id,
  required String taskId,
  required String text,
  required int seq,
}) {
  final timestamp = DateTime.fromMillisecondsSinceEpoch(1000);
  return ChatMessageModel(
    id: id,
    type: 1,
    user: 2,
    content: <String, dynamic>{'text': text, 'id': id},
    createAt: timestamp,
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': 'text_snapshot',
      'entryId': id,
      'seq': seq,
      'roundIndex': 1,
      'isFinal': false,
    },
  );
}

ChatMessageModel _thinkingCard({
  required String id,
  required String taskId,
  required int seq,
  String thinking = '思考',
}) {
  return ChatMessageModel.cardMessage(
    <String, dynamic>{
      'type': 'deep_thinking',
      'taskID': taskId,
      'cardId': id,
      'thinkingContent': thinking,
      'isLoading': seq == 1,
      'stage': seq == 1 ? 1 : 4,
    },
    id: id,
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': 'thinking_snapshot',
      'entryId': id,
      'seq': seq,
      'roundIndex': 1,
      'isFinal': false,
    },
  ).copyWith(createAt: DateTime.fromMillisecondsSinceEpoch(1000));
}
