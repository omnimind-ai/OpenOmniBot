import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_stream_reducer.dart';

void main() {
  AgentStreamEvent toolEvent({
    required int seq,
    required AgentStreamEventKind kind,
    required String entryId,
    Map<String, dynamic> raw = const <String, dynamic>{},
  }) {
    return AgentStreamEvent(
      taskId: 'agent-task',
      seq: seq,
      kind: kind,
      createdAtMs: seq,
      entryId: entryId,
      roundIndex: 1,
      raw: raw,
    );
  }

  test('tracks only running tool entries as active', () {
    const reducer = AgentStreamReducer();

    final afterFirstStart = reducer.reduce(
      null,
      toolEvent(
        seq: 1,
        kind: AgentStreamEventKind.toolStarted,
        entryId: 'tool-1',
      ),
    );
    expect(afterFirstStart.nextState.activeToolEntryIds, {'tool-1'});

    final afterSecondStart = reducer.reduce(
      afterFirstStart.nextState,
      toolEvent(
        seq: 2,
        kind: AgentStreamEventKind.toolStarted,
        entryId: 'tool-2',
      ),
    );
    expect(afterSecondStart.nextState.activeToolEntryIds, {'tool-1', 'tool-2'});

    final afterFirstComplete = reducer.reduce(
      afterSecondStart.nextState,
      toolEvent(
        seq: 3,
        kind: AgentStreamEventKind.toolCompleted,
        entryId: 'tool-1',
      ),
    );
    expect(afterFirstComplete.nextState.toolCards.keys, contains('tool-1'));
    expect(afterFirstComplete.nextState.activeToolEntryIds, {'tool-2'});

    final afterSecondComplete = reducer.reduce(
      afterFirstComplete.nextState,
      toolEvent(
        seq: 4,
        kind: AgentStreamEventKind.toolCompleted,
        entryId: 'tool-2',
      ),
    );
    expect(afterSecondComplete.nextState.toolCards.keys, contains('tool-2'));
    expect(afterSecondComplete.nextState.activeToolEntryIds, isEmpty);
  });

  test('clears active tool entries on terminal agent states', () {
    const reducer = AgentStreamReducer();
    final running = reducer.reduce(
      null,
      toolEvent(
        seq: 1,
        kind: AgentStreamEventKind.toolProgress,
        entryId: 'tool-1',
      ),
    );

    final completed = reducer.reduce(
      running.nextState,
      const AgentStreamEvent(
        taskId: 'agent-task',
        seq: 2,
        kind: AgentStreamEventKind.completed,
        createdAtMs: 2,
      ),
    );

    expect(completed.nextState.activeToolEntryIds, isEmpty);
  });

  test('uses toolCallId as canonical tool identity across event entry ids', () {
    const reducer = AgentStreamReducer();

    final started = reducer.reduce(
      null,
      toolEvent(
        seq: 1,
        kind: AgentStreamEventKind.toolStarted,
        entryId: 'entry-start',
        raw: const {'toolCallId': 'tool-call-1'},
      ),
    );
    expect(started.nextState.toolCards.keys, {'tool-call-1'});
    expect(started.nextState.activeToolEntryIds, {'tool-call-1'});

    final completed = reducer.reduce(
      started.nextState,
      toolEvent(
        seq: 2,
        kind: AgentStreamEventKind.toolCompleted,
        entryId: 'entry-complete',
        raw: const {'toolCallId': 'tool-call-1'},
      ),
    );

    expect(completed.nextState.toolCards.keys, {'tool-call-1'});
    expect(completed.nextState.activeToolEntryIds, isEmpty);
  });
}
