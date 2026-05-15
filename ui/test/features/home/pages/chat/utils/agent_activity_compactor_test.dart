import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_activity_compactor.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test('compacts consecutive browser tool calls into one activity', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-1-tool-1',
        seq: 1,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"navigate","url":"https://example.com"}',
        status: 'success',
      ),
      _toolMessage(
        id: 'task-1-tool-2',
        seq: 2,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"click","selector":"#login"}',
        status: 'success',
      ),
    ]);

    expect(items, hasLength(1));
    final activity = items.single.activity;
    expect(activity, isNotNull);
    expect(activity?.kind, AgentToolActivityKind.browser);
    expect(activity?.status, 'success');
    expect(activity?.stepCount, 2);
    expect(activity?.steps.map((step) => step.action), ['navigate', 'click']);
  });

  test('does not compact a single tool call', () {
    final message = _toolMessage(
      id: 'task-2-tool-1',
      seq: 1,
      toolType: 'browser',
      toolName: 'browser_use',
      argsJson: '{"action":"navigate","url":"https://example.com"}',
    );

    final items = compactAgentProcessItems(<ChatMessageModel>[message]);

    expect(items, hasLength(1));
    expect(items.single.message?.id, message.id);
    expect(items.single.activity, isNull);
  });

  test('marks repeated failed browser action as retry', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-3-tool-1',
        seq: 1,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"click","selector":"#login"}',
        status: 'error',
      ),
      _toolMessage(
        id: 'task-3-tool-2',
        seq: 2,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"click","selector":"#login"}',
        status: 'success',
      ),
    ]);

    final steps = items.single.activity?.steps;
    expect(steps, hasLength(2));
    expect(steps?.first.isRetry, isFalse);
    expect(steps?.last.isRetry, isTrue);
    expect(items.single.activity?.status, 'success');
  });

  test('ignores stale interrupted browser preview placeholders', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-6-tool-1',
        seq: 1,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"navigate","url":"https://example.com"}',
        status: 'success',
      ),
      _toolMessage(
        id: 'task-6-tool-2',
        seq: 2,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{}',
        status: 'interrupted',
        summary: 'Preparing tool call...',
        streamKind: 'tool_started',
      ),
      _toolMessage(
        id: 'task-6-tool-3',
        seq: 3,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"get_text","selector":"body"}',
        status: 'success',
      ),
    ]);

    expect(items, hasLength(1));
    final activity = items.single.activity;
    expect(activity?.status, 'success');
    expect(activity?.stepCount, 2);
    expect(activity?.steps.map((step) => step.cardId), [
      'task-6-tool-1',
      'task-6-tool-3',
    ]);
  });

  test('groups terminal steps by session id', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-4-tool-1',
        seq: 1,
        toolType: 'terminal',
        toolName: 'terminal_session_exec',
        argsJson: '{"command":"ls","terminalSessionId":"s1"}',
      ),
      _toolMessage(
        id: 'task-4-tool-2',
        seq: 2,
        toolType: 'terminal',
        toolName: 'terminal_session_read',
        argsJson: '{"terminalSessionId":"s1"}',
      ),
      _toolMessage(
        id: 'task-4-tool-3',
        seq: 3,
        toolType: 'terminal',
        toolName: 'terminal_session_exec',
        argsJson: '{"command":"pwd","terminalSessionId":"s2"}',
      ),
    ]);

    expect(items, hasLength(2));
    expect(items.first.activity?.kind, AgentToolActivityKind.terminal);
    expect(items.first.activity?.stepCount, 2);
    expect(items.last.message?.id, 'task-4-tool-3');
  });

  test('non-tool messages break activity compaction', () {
    final thinking = ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'cardId': 'task-5-thinking',
    }, id: 'task-5-thinking');
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-5-tool-1',
        seq: 1,
        toolType: 'browser',
        toolName: 'browser_use',
      ),
      thinking,
      _toolMessage(
        id: 'task-5-tool-2',
        seq: 2,
        toolType: 'browser',
        toolName: 'browser_use',
      ),
    ]);

    expect(items, hasLength(3));
    expect(items.where((item) => item.activity != null), isEmpty);
  });
}

ChatMessageModel _toolMessage({
  required String id,
  required int seq,
  required String toolType,
  required String toolName,
  String argsJson = '{}',
  String status = 'success',
  String summary = '',
  String streamKind = 'tool_completed',
}) {
  return ChatMessageModel.cardMessage(
    <String, dynamic>{
      'type': 'agent_tool_summary',
      'taskId': 'task-${id.split('-')[1]}',
      'cardId': id,
      'toolType': toolType,
      'toolName': toolName,
      'argsJson': argsJson,
      'status': status,
      if (summary.isNotEmpty) 'summary': summary,
    },
    id: id,
    streamMeta: <String, dynamic>{
      'parentTaskId': 'task-${id.split('-')[1]}',
      'entryId': id,
      'kind': streamKind,
      'seq': seq,
    },
  );
}
