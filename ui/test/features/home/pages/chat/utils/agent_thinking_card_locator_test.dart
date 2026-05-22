import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_thinking_card_locator.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test(
    'prefers the explicit active thinking entry when it is still present',
    () {
      final messages = <ChatMessageModel>[
        ChatMessageModel.cardMessage(
          <String, dynamic>{'type': 'deep_thinking'},
          id: 'agent-task-thinking-2',
          streamMeta: const <String, dynamic>{'roundIndex': 2, 'seq': 4},
        ),
        ChatMessageModel.cardMessage(
          <String, dynamic>{'type': 'deep_thinking'},
          id: 'agent-task-thinking',
          streamMeta: const <String, dynamic>{'roundIndex': 1, 'seq': 2},
        ),
      ];

      final resolved = resolveAgentThinkingCardForTask(
        messages,
        taskId: 'agent-task',
        preferredCardId: 'agent-task-thinking',
      );

      expect(resolved?.id, 'agent-task-thinking');
    },
  );

  test(
    'falls back to the latest thinking round when no active entry id exists',
    () {
      final messages = <ChatMessageModel>[
        ChatMessageModel.cardMessage(
          <String, dynamic>{'type': 'deep_thinking'},
          id: 'agent-task-thinking',
          streamMeta: const <String, dynamic>{'roundIndex': 1, 'seq': 2},
        ),
        ChatMessageModel.cardMessage(
          <String, dynamic>{'type': 'deep_thinking'},
          id: 'agent-task-thinking-3',
          streamMeta: const <String, dynamic>{'roundIndex': 3, 'seq': 6},
        ),
        ChatMessageModel.cardMessage(
          <String, dynamic>{'type': 'deep_thinking'},
          id: 'agent-task-thinking-2',
          streamMeta: const <String, dynamic>{'roundIndex': 2, 'seq': 4},
        ),
        ChatMessageModel.cardMessage(
          <String, dynamic>{'type': 'deep_thinking'},
          id: 'other-task-thinking',
          streamMeta: const <String, dynamic>{'roundIndex': 9, 'seq': 99},
        ),
      ];

      final resolved = resolveAgentThinkingCardForTask(
        messages,
        taskId: 'agent-task',
      );

      expect(resolved?.id, 'agent-task-thinking-3');
    },
  );

  test('matches restored thinking cards by task metadata', () {
    final messages = <ChatMessageModel>[
      ChatMessageModel.cardMessage(
        <String, dynamic>{'type': 'deep_thinking', 'taskID': 'agent-task'},
        id: 'restored-thinking-card',
        streamMeta: const <String, dynamic>{'roundIndex': 1, 'seq': 2},
      ),
    ];

    final resolved = resolveAgentThinkingCardForTask(
      messages,
      taskId: 'agent-task',
    );

    expect(resolved?.id, 'restored-thinking-card');
  });
}
