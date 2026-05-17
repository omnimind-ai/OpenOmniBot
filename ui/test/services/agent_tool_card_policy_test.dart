import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
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
}
