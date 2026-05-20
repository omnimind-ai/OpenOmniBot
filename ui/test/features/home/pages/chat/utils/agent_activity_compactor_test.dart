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

  test('wraps a single tool call as a one-step Activity', () {
    final message = _toolMessage(
      id: 'task-2-tool-1',
      seq: 1,
      toolType: 'browser',
      toolName: 'browser_use',
      argsJson: '{"action":"navigate","url":"https://example.com"}',
    );

    final items = compactAgentProcessItems(<ChatMessageModel>[message]);

    expect(items, hasLength(1));
    expect(items.single.activity, isNotNull);
    expect(items.single.activity?.kind, AgentToolActivityKind.browser);
    expect(items.single.activity?.stepCount, 1);
  });

  test('wraps unknown codex tool type into generic activity', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-9-tool-1',
        seq: 1,
        toolType: 'plan',
        toolName: 'codex.plan',
        summary: 'Review repository layout',
      ),
    ]);

    expect(items, hasLength(1));
    expect(items.single.activity?.kind, AgentToolActivityKind.generic);
    expect(items.single.activity?.stepCount, 1);
    expect(
      items.single.activity?.steps.single.title,
      'Review repository layout',
    );
  });

  test(
    'folded activity title prefers semantic target over placeholder text',
    () {
      final items = compactAgentProcessItems(<ChatMessageModel>[
        _toolMessage(
          id: 'task-8-tool-1',
          seq: 1,
          toolType: 'terminal',
          toolName: 'terminal_session_exec',
          argsJson: '{"command":"flutter test ui/test/services"}',
          status: 'success',
          summary: 'Preparing tool call...',
        ),
      ]);

      final activity = items.single.activity;
      expect(activity?.title, 'flutter test ui/test/services');
      expect(activity?.steps.single.title, 'flutter test ui/test/services');
    },
  );

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

    // s2 has only one step — still surfaces as a single-step Activity.
    expect(items, hasLength(2));
    expect(items.first.activity?.kind, AgentToolActivityKind.terminal);
    expect(items.first.activity?.stepCount, 2);
    expect(items.last.activity?.kind, AgentToolActivityKind.terminal);
    expect(items.last.activity?.stepCount, 1);
  });

  test('keeps web search activity separate from browser activity', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-7-tool-1',
        seq: 1,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"navigate","url":"https://example.com"}',
      ),
      _toolMessage(
        id: 'task-7-tool-2',
        seq: 2,
        toolType: 'browser',
        toolName: 'browser_use',
        argsJson: '{"action":"get_text","selector":"body"}',
      ),
      _toolMessage(
        id: 'task-7-tool-3',
        seq: 3,
        toolType: 'research',
        toolName: 'web_search',
        argsJson: '{"query":"todo list app features"}',
      ),
      _toolMessage(
        id: 'task-7-tool-4',
        seq: 4,
        toolType: 'research',
        toolName: 'web_search',
        argsJson: '{"query":"open source todo app github"}',
      ),
    ]);

    expect(items, hasLength(2));
    expect(items.first.activity?.kind, AgentToolActivityKind.browser);
    expect(items.first.activity?.stepCount, 2);
    expect(items.last.activity?.kind, AgentToolActivityKind.research);
    expect(items.last.activity?.stepCount, 2);
    expect(items.last.activity?.steps.map((step) => step.action), [
      'search',
      'search',
    ]);
    expect(items.last.activity?.steps.map((step) => step.target), [
      'todo list app features',
      'open source todo app github',
    ]);
  });

  test('compacts VLM steps as visual activity instead of research', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      _toolMessage(
        id: 'task-vlm-tool-1',
        seq: 1,
        toolType: 'vlm',
        toolName: 'click',
        argsJson: '{"target_description":"设置按钮","x":120,"y":240}',
        status: 'success',
        summary: '点击 设置按钮',
      ),
      _toolMessage(
        id: 'task-vlm-tool-2',
        seq: 2,
        toolType: 'vlm',
        toolName: 'type',
        argsJson: '{"content":"hello"}',
        status: 'running',
        summary: '输入文本',
      ),
    ]);

    expect(items, hasLength(1));
    final activity = items.single.activity;
    expect(activity?.kind, AgentToolActivityKind.vlm);
    expect(activity?.title, 'hello');
    expect(activity?.status, 'running');
    expect(activity?.stepCount, 2);
    expect(activity?.steps.map((step) => step.action), ['click', 'type']);
    expect(activity?.steps.map((step) => step.target), ['设置按钮', 'hello']);
  });

  test('compacts VLM runlog cards even when taskId is absent', () {
    final items = compactAgentProcessItems(<ChatMessageModel>[
      ChatMessageModel.cardMessage(
        const <String, dynamic>{
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'vlm',
          'toolName': 'click',
          'cardId': 'run-vlm-step-1',
          'runLogId': 'run-vlm',
          'argsJson': '{"target_description":"设置按钮"}',
        },
        id: 'run-vlm-step-1',
        streamMeta: const <String, dynamic>{
          'runLogId': 'run-vlm',
          'entryId': 'run-vlm-step-1',
          'kind': 'tool_completed',
          'seq': 1,
        },
      ),
      ChatMessageModel.cardMessage(
        const <String, dynamic>{
          'type': 'agent_tool_summary',
          'status': 'running',
          'toolName': 'vlm_task',
          'cardId': 'run-vlm-step-2',
          'run_id': 'run-vlm',
          'compile_kind': 'vlm',
          'argsJson': '{"goal":"打开设置"}',
        },
        id: 'run-vlm-step-2',
        streamMeta: const <String, dynamic>{
          'run_id': 'run-vlm',
          'entryId': 'run-vlm-step-2',
          'kind': 'tool_progress',
          'seq': 2,
        },
      ),
    ]);

    expect(items, hasLength(1));
    final activity = items.single.activity;
    expect(activity?.kind, AgentToolActivityKind.vlm);
    expect(activity?.taskId, 'run-vlm');
    expect(activity?.stepCount, 2);
    expect(activity?.steps.map((step) => step.target), ['设置按钮', '打开设置']);
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

    // A non-tool message breaks grouping — the two browser calls are separate
    // single-step Activities rather than one merged group.
    expect(items, hasLength(3));
    expect(items.where((item) => item.activity != null), hasLength(2));
    expect(items[0].activity?.stepCount, 1);
    expect(items[1].message, isNotNull); // thinking card
    expect(items[2].activity?.stepCount, 1);
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
