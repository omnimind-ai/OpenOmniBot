import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_card_policy.dart';

void main() {
  test('operation id prefers stable tool identifiers over entry id', () {
    const event = AgentStreamEvent(
      taskId: 'task-1',
      seq: 1,
      kind: AgentStreamEventKind.toolCompleted,
      createdAtMs: 1,
      entryId: 'entry-complete',
      raw: {'toolCallId': 'call-1'},
    );

    expect(AgentToolCardPolicy.operationIdFromEvent(event), 'call-1');
    expect(AgentToolCardPolicy.cardIdForEvent(event), 'call-1');
  });

  test('card identity matching includes nested runlog tool call ids', () {
    final card = <String, dynamic>{
      'cardId': 'card-1',
      'tool_call': {'id': 'nested-call-1'},
    };

    expect(AgentToolCardPolicy.cardMatchesId(card, 'nested-call-1'), isTrue);
    expect(AgentToolCardPolicy.cardMatchesId(card, 'missing'), isFalse);
  });

  test('task id prefers agent parent task over nested tool run id', () {
    final message = ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'taskId': 'vlm-run-1',
        'runLogId': 'vlm-run-1',
        'cardId': 'vlm-run-1-vlm-1',
      },
      id: 'vlm-run-1-vlm-1',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'agent-run-1',
        'runLogId': 'vlm-run-1',
      },
    );

    expect(AgentToolCardPolicy.taskIdForMessage(message), 'agent-run-1');
    expect(
      AgentToolCardPolicy.runLogRef(
        message.cardData,
        message: message,
      ).runLogId,
      'vlm-run-1',
    );
  });

  test('task id reads embedded stream meta before nested card task id', () {
    final message = ChatMessageModel.cardMessage(const <String, dynamic>{
      'type': 'agent_tool_summary',
      'taskId': 'vlm-run-2',
      'runLogId': 'vlm-run-2',
      'cardId': 'vlm-run-2-vlm-1',
      'streamMeta': <String, dynamic>{
        'parentTaskId': 'agent-run-2',
        'runLogId': 'agent-run-2',
      },
    }, id: 'vlm-run-2-vlm-1');

    expect(AgentToolCardPolicy.taskIdForMessage(message), 'agent-run-2');
  });
}
