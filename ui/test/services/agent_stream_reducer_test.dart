import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_stream_reducer.dart';

void main() {
  test('parses v1 envelope aliases while preserving legacy fields', () {
    final event = AgentStreamEvent.fromMap(const {
      'schema_version': 'oob.agent_event.v1',
      'trace_id': 'trace-1',
      'run_id': 'run-1',
      'span_id': 'span-1',
      'parent_span_id': 'root',
      'channel': 'agent_stream',
      'event': 'text_snapshot',
      'timestamp_ms': 1234,
      'status': 'running',
      'taskId': 'agent-task',
      'seq': 7,
      'entryId': 'agent-task-text',
      'text': 'hello',
    });

    expect(event.kind, AgentStreamEventKind.textSnapshot);
    expect(event.createdAtMs, 1234);
    expect(event.schemaVersion, 'oob.agent_event.v1');
    expect(event.traceId, 'trace-1');
    expect(event.runId, 'run-1');
    expect(event.spanId, 'span-1');
    expect(event.parentSpanId, 'root');
    expect(event.channel, 'agent_stream');
    expect(event.eventName, 'text_snapshot');
    expect(event.status, 'running');
  });

  test('parses snake_case agent event envelopes used by run log streams', () {
    final event = AgentStreamEvent.fromMap(const {
      'schema_version': 'oob.agent_event.v1',
      'trace_id': 'trace-2',
      'run_id': 'run-2',
      'span_id': 'span-2',
      'parent_span_id': 'root',
      'channel': 'agent_stream',
      'event': 'tool_progress',
      'timestamp_ms': 4321,
      'status': 'running',
      'task_id': 'agent-task-snake',
      'sequence': 9,
      'entry_id': 'agent-task-snake-tool',
      'round_index': 2,
      'is_final': false,
      'tool_type': 'vlm',
    });

    expect(event.kind, AgentStreamEventKind.toolProgress);
    expect(event.taskId, 'agent-task-snake');
    expect(event.seq, 9);
    expect(event.entryId, 'agent-task-snake-tool');
    expect(event.roundIndex, 2);
    expect(event.runId, 'run-2');
    expect(event.spanId, 'span-2');
    expect(event.isFinal, isFalse);
  });

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

  test('replaces entry-id fallback when later events reveal toolCallId', () {
    const reducer = AgentStreamReducer();

    final started = reducer.reduce(
      null,
      toolEvent(
        seq: 1,
        kind: AgentStreamEventKind.toolStarted,
        entryId: 'entry-start',
      ),
    );
    expect(started.nextState.toolCards.keys, {'entry-start'});
    expect(started.nextState.activeToolEntryIds, {'entry-start'});
    expect(started.nextState.activeToolFallbackEntryIds, {'entry-start'});

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
    expect(completed.nextState.activeToolFallbackEntryIds, isEmpty);
  });

  test('keeps ambiguous fallback tool entries when a stable id appears', () {
    const reducer = AgentStreamReducer();

    final firstStarted = reducer.reduce(
      null,
      toolEvent(
        seq: 1,
        kind: AgentStreamEventKind.toolStarted,
        entryId: 'entry-start-1',
      ),
    );
    final secondStarted = reducer.reduce(
      firstStarted.nextState,
      toolEvent(
        seq: 2,
        kind: AgentStreamEventKind.toolStarted,
        entryId: 'entry-start-2',
      ),
    );

    final completed = reducer.reduce(
      secondStarted.nextState,
      toolEvent(
        seq: 3,
        kind: AgentStreamEventKind.toolCompleted,
        entryId: 'entry-complete',
        raw: const {'toolCallId': 'tool-call-1'},
      ),
    );

    expect(completed.nextState.toolCards.keys, {
      'entry-start-1',
      'entry-start-2',
      'tool-call-1',
    });
    expect(completed.nextState.activeToolEntryIds, {
      'entry-start-1',
      'entry-start-2',
    });
    expect(completed.nextState.activeToolFallbackEntryIds, {
      'entry-start-1',
      'entry-start-2',
    });
  });
}
