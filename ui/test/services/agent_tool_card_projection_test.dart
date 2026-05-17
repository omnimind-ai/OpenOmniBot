import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_card_projection.dart';

void main() {
  test('appends terminal output deltas onto the existing tool card', () {
    final messages = <ChatMessageModel>[];

    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolStarted,
        taskId: 'task-1',
        seq: 1,
        raw: const <String, dynamic>{
          'toolCallId': 'call-1',
          'toolName': 'terminal_execute',
          'toolType': 'terminal',
          'summary': 'Run command',
        },
      ),
    );
    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolProgress,
        taskId: 'task-1',
        seq: 2,
        raw: const <String, dynamic>{
          'toolCallId': 'call-1',
          'toolName': 'terminal_execute',
          'toolType': 'terminal',
          'terminalOutputDelta': 'hello\n',
        },
      ),
    );
    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolProgress,
        taskId: 'task-1',
        seq: 3,
        raw: const <String, dynamic>{
          'toolCallId': 'call-1',
          'toolName': 'terminal_execute',
          'toolType': 'terminal',
          'terminalOutputDelta': 'world\n',
        },
      ),
    );

    expect(messages, hasLength(1));
    expect(messages.single.cardData?['terminalOutput'], 'hello\nworld\n');
    expect(messages.single.cardData?['status'], 'running');
  });

  test('merges a completed event into an entry-id placeholder', () {
    final messages = <ChatMessageModel>[];

    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolStarted,
        taskId: 'task-2',
        seq: 1,
        entryId: 'task-2-tool-start',
        raw: const <String, dynamic>{
          'toolName': 'browser_use',
          'toolType': 'browser',
          'summary': 'Preparing tool call...',
        },
      ),
    );
    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolCompleted,
        taskId: 'task-2',
        seq: 2,
        entryId: 'task-2-tool-complete',
        raw: const <String, dynamic>{
          'toolCallId': 'call-2',
          'toolName': 'browser_use',
          'toolType': 'browser',
          'summary': 'Opened page',
          'success': true,
        },
      ),
    );

    expect(messages, hasLength(1));
    expect(messages.single.id, 'call-2');
    expect(messages.single.cardData?['cardId'], 'call-2');
    expect(messages.single.cardData?['summary'], 'Opened page');
    expect(messages.single.cardData?['status'], 'success');
    expect(messages.single.streamMeta?['entryId'], 'call-2');
  });

  test('keeps args and resolves tool title from args metadata', () {
    final messages = <ChatMessageModel>[];
    final argsJson = jsonEncode(<String, dynamic>{
      'tool_title': '查看配置',
      'path': 'README.md',
    });

    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolStarted,
        taskId: 'task-3',
        seq: 1,
        raw: <String, dynamic>{
          'toolCallId': 'call-3',
          'toolName': 'file_read',
          'displayName': '读取文件',
          'toolType': 'workspace',
          'argsJson': argsJson,
        },
      ),
    );
    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolCompleted,
        taskId: 'task-3',
        seq: 2,
        raw: const <String, dynamic>{
          'toolCallId': 'call-3',
          'toolName': 'file_read',
          'toolType': 'workspace',
          'resultPreviewJson': '{"ok":true}',
        },
      ),
    );

    expect(messages, hasLength(1));
    expect(messages.single.cardData?['toolTitle'], '查看配置');
    expect(messages.single.cardData?['argsJson'], argsJson);
    expect(messages.single.cardData?['resultPreviewJson'], '{"ok":true}');
  });

  test('normalizes failed terminal status to error', () {
    final projection = AgentToolCardProjection.projectStreamEvent(
      event: _event(
        kind: AgentStreamEventKind.toolCompleted,
        taskId: 'task-4',
        seq: 1,
        raw: const <String, dynamic>{
          'toolCallId': 'call-4',
          'toolName': 'terminal_execute',
          'toolType': 'terminal',
          'status': 'failed',
          'success': false,
        },
      ),
      messages: const <ChatMessageModel>[],
      defaultRunningSummary: 'Calling tool',
    );

    expect(projection, isNotNull);
    expect(projection!.cardData['status'], 'error');
    expect(projection.cardData['success'], isFalse);
  });

  test('completed card does not keep the default running summary', () {
    final messages = <ChatMessageModel>[];

    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolStarted,
        taskId: 'task-5',
        seq: 1,
        raw: const <String, dynamic>{
          'toolCallId': 'call-5',
          'toolName': 'terminal_execute',
          'toolType': 'terminal',
        },
      ),
    );
    _upsert(
      messages,
      _event(
        kind: AgentStreamEventKind.toolCompleted,
        taskId: 'task-5',
        seq: 2,
        raw: const <String, dynamic>{
          'toolCallId': 'call-5',
          'toolName': 'terminal_execute',
          'toolType': 'terminal',
          'success': true,
        },
      ),
    );

    expect(messages.single.cardData?['summary'], isEmpty);
    expect(messages.single.cardData?['status'], 'success');
  });
}

AgentStreamEvent _event({
  required AgentStreamEventKind kind,
  required String taskId,
  required int seq,
  String? entryId,
  Map<String, dynamic> raw = const <String, dynamic>{},
}) {
  return AgentStreamEvent(
    taskId: taskId,
    seq: seq,
    kind: kind,
    createdAtMs: 1000 + seq,
    entryId: entryId,
    raw: <String, dynamic>{'taskId': taskId, 'seq': seq, ...raw},
  );
}

void _upsert(List<ChatMessageModel> messages, AgentStreamEvent event) {
  final projection = AgentToolCardProjection.projectStreamEvent(
    event: event,
    messages: messages,
    defaultRunningSummary: 'Calling tool',
  );
  expect(projection, isNotNull);
  if (projection!.isInsert) {
    messages.add(
      ChatMessageModel.cardMessage(
        projection.cardData,
        id: projection.cardId,
        streamMeta: projection.streamMeta,
      ),
    );
  } else {
    messages[projection.existingIndex] = messages[projection.existingIndex]
        .copyWith(
          id: projection.cardId,
          content: <String, dynamic>{
            'cardData': projection.cardData,
            'id': projection.cardId,
          },
          streamMeta: projection.streamMeta,
        );
  }
}
