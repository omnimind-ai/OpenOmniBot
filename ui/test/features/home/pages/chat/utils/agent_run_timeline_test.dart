import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test('groups completed agent run by parent task id', () {
    final entries = buildAgentRunTimelineEntries(_buildCompletedRunMessages());

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-1');
    expect(entries.first.group?.thinkingCount, 1);
    expect(entries.first.group?.toolCount, 1);
    expect(entries.first.group?.visibleMessagesNewestFirst.single.text, '最终回答');
  });

  test('falls back to latest text snapshot when history lacks isFinal', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-2-text-2',
        text: '第二版回答',
        taskId: 'task-2',
        kind: 'text_snapshot',
        seq: 22,
        isFinal: null,
      ),
      _assistantMessage(
        id: 'task-2-text-1',
        text: '第一版回答',
        taskId: 'task-2',
        kind: 'text_snapshot',
        seq: 21,
        isFinal: null,
      ),
      _thinkingCard(id: 'task-2-thinking', taskId: 'task-2', seq: 12),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(
      entries.single.group?.visibleMessagesNewestFirst.single.id,
      'task-2-text-2',
    );
  });

  test('groups in-flight process messages while task is active', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: false),
      activeTaskIds: const <String>{'task-1'},
    );

    expect(entries, hasLength(2));
    final group = entries.first.group;
    expect(group, isNotNull);
    expect(group?.taskId, 'task-1');
    expect(group?.isActiveRun, isTrue);
    expect(group?.visibleMessagesNewestFirst.single.id, 'task-1-text');
    expect(group?.processMessagesNewestFirst.map((message) => message.id), [
      'task-1-tool',
      'task-1-thinking',
    ]);
  });

  test('keeps active final snapshot inside the same running group', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: true),
      activeTaskIds: const <String>{'task-1'},
    );

    expect(entries, hasLength(2));
    expect(entries.first.group?.isActiveRun, isTrue);
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.id,
      'task-1-text',
    );
  });

  test('groups active thinking-only task into one running entry', () {
    final messages = <ChatMessageModel>[
      _thinkingCard(id: 'task-5-thinking', taskId: 'task-5', seq: 10),
      ChatMessageModel.userMessage('用户问题', id: 'user-5'),
    ];

    final entries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'task-5'},
    );

    expect(entries, hasLength(2));
    final group = entries.first.group;
    expect(group?.isActiveRun, isTrue);
    expect(group?.visibleMessagesNewestFirst, isEmpty);
    expect(group?.processMessagesNewestFirst.single.id, 'task-5-thinking');
  });

  test('keeps only latest duplicate thinking round for a running task', () {
    final messages = <ChatMessageModel>[
      _cardMessage(
        id: 'task-6-tool',
        taskId: 'task-6',
        kind: 'tool_started',
        seq: 30,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'status': 'running',
          'toolType': 'workspace',
        },
      ),
      _thinkingCard(id: 'task-6-thinking-copy', taskId: 'task-6', seq: 20),
      _thinkingCard(id: 'task-6-thinking', taskId: 'task-6', seq: 10),
      ChatMessageModel.userMessage('用户问题', id: 'user-6'),
    ];

    final entries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'task-6'},
    );

    final processIds = entries.first.group?.processMessagesNewestFirst.map(
      (message) => message.id,
    );
    expect(processIds, ['task-6-tool', 'task-6-thinking-copy']);
  });

  test('keeps distinct thinking rounds in the same run', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-6b-text',
        text: '最终回答',
        taskId: 'task-6b',
        kind: 'text_snapshot',
        seq: 40,
        isFinal: true,
      ),
      _thinkingCard(id: 'task-6b-thinking-2', taskId: 'task-6b', seq: 20),
      _thinkingCard(id: 'task-6b-thinking', taskId: 'task-6b', seq: 10),
      ChatMessageModel.userMessage('用户问题', id: 'user-6b'),
    ];

    final group = buildAgentRunTimelineEntries(messages).first.group;

    expect(group?.thinkingCount, 2);
    expect(group?.processMessagesNewestFirst.map((message) => message.id), [
      'task-6b-thinking-2',
      'task-6b-thinking',
    ]);
  });

  test('keeps all tool cards while compacting duplicate thinking cards', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-7-text',
        text: '最终回答',
        taskId: 'task-7',
        kind: 'text_snapshot',
        seq: 50,
        isFinal: true,
      ),
      _cardMessage(
        id: 'task-7-tool-2',
        taskId: 'task-7',
        kind: 'tool_completed',
        seq: 40,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'taskId': 'task-7',
          'status': 'success',
          'toolType': 'terminal',
          'toolTitle': '执行命令',
          'terminalOutput': 'done',
        },
      ),
      _cardMessage(
        id: 'task-7-tool-1',
        taskId: 'task-7',
        kind: 'tool_completed',
        seq: 30,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'taskId': 'task-7',
          'status': 'success',
          'toolType': 'workspace',
          'toolTitle': '读取文件',
        },
      ),
      _thinkingCard(id: 'task-7-thinking-copy', taskId: 'task-7', seq: 20),
      _thinkingCard(id: 'task-7-thinking', taskId: 'task-7', seq: 10),
    ];

    final group = buildAgentRunTimelineEntries(messages).single.group;

    expect(group?.thinkingCount, 1);
    expect(group?.toolCount, 2);
    expect(group?.processMessagesNewestFirst.map((message) => message.id), [
      'task-7-tool-2',
      'task-7-tool-1',
      'task-7-thinking-copy',
    ]);
  });

  test('uses card task ids when restored cards lack stream metadata', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-8-text',
        text: '最终回答',
        taskId: 'task-8',
        kind: 'text_snapshot',
        seq: 50,
        isFinal: true,
      ),
      _cardMessage(
        id: 'task-8-tool',
        taskId: 'task-8',
        kind: 'tool_completed',
        seq: 30,
        includeStreamMeta: false,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'taskId': 'task-8',
          'status': 'success',
          'toolType': 'terminal',
          'toolTitle': '执行命令',
          'terminalOutput': 'stdout',
        },
      ),
      _cardMessage(
        id: 'task-8-thinking',
        taskId: 'task-8',
        kind: 'thinking_snapshot',
        seq: 10,
        includeStreamMeta: false,
        cardData: <String, dynamic>{
          'type': 'deep_thinking',
          'thinkingContent': '思考过程',
          'stage': 4,
          'isLoading': false,
          'taskID': 'task-8',
          'cardId': 'task-8-thinking',
        },
      ),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(entries.single.group?.toolCount, 1);
    expect(entries.single.group?.thinkingCount, 1);
  });

  test(
    'does not fold persisted partial snapshot with explicit non-final flag',
    () {
      final messages = <ChatMessageModel>[
        _assistantMessage(
          id: 'task-4-text',
          text: '未完成回答',
          taskId: 'task-4',
          kind: 'text_snapshot',
          seq: 22,
          isFinal: false,
        ),
        _thinkingCard(id: 'task-4-thinking', taskId: 'task-4', seq: 12),
        ChatMessageModel.userMessage('用户问题', id: 'user-4'),
      ];

      final entries = buildAgentRunTimelineEntries(messages);

      expect(entries, hasLength(3));
      expect(entries.where((entry) => entry.group != null), isEmpty);
    },
  );

  test('keeps permission card visible alongside final permission text', () {
    final messages = <ChatMessageModel>[
      _cardMessage(
        id: 'task-3-permission-card',
        taskId: 'task-3',
        kind: 'permission_required',
        seq: 31,
        cardData: <String, dynamic>{
          'type': 'permission_section',
          'requiredPermissionIds': const <String>['overlay'],
        },
      ),
      _assistantMessage(
        id: 'task-3-permission-text',
        text: '请先授权',
        taskId: 'task-3',
        kind: 'permission_required',
        seq: 30,
        isFinal: true,
      ),
      _thinkingCard(id: 'task-3-thinking', taskId: 'task-3', seq: 10),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(entries.single.group?.visibleMessagesNewestFirst, hasLength(2));
    expect(
      entries.single.group?.visibleMessagesNewestFirst.map(
        (message) => message.id,
      ),
      containsAll(<String>['task-3-permission-card', 'task-3-permission-text']),
    );
  });

  test(
    'auto-expands a run only after it transitions from active to completed',
    () {
      final tracker = AgentRunCompletionExpansionTracker();
      final activeMessages = _buildCompletedRunMessages(isFinal: false);

      expect(
        tracker.sync(
          messages: activeMessages,
          activeTaskIds: const <String>{'task-1'},
        ),
        isFalse,
      );
      expect(tracker.isTaskExpanded('task-1', const <String>{}), isFalse);

      expect(
        tracker.sync(
          messages: _buildCompletedRunMessages(),
          activeTaskIds: const <String>{},
        ),
        isTrue,
      );
      expect(tracker.isTaskExpanded('task-1', const <String>{}), isTrue);

      tracker.consumeAutoExpandedTask('task-1');
      expect(tracker.isTaskExpanded('task-1', const <String>{}), isFalse);
    },
  );

  test(
    'uses cancelled text as the visible body for a manually stopped run',
    () {
      final messages = <ChatMessageModel>[
        _assistantMessage(
          id: 'task-5-cancelled',
          text: '任务已取消',
          taskId: 'task-5',
          kind: 'text_snapshot',
          seq: 1000000000,
          isFinal: true,
        ),
        _thinkingCard(id: 'task-5-thinking', taskId: 'task-5', seq: 12),
      ];

      final entries = buildAgentRunTimelineEntries(messages);

      expect(entries, hasLength(1));
      expect(
        entries.single.group?.visibleMessagesNewestFirst.single.text,
        '任务已取消',
      );
      expect(
        entries.single.group?.processMessagesNewestFirst.single.id,
        'task-5-thinking',
      );
    },
  );
}

List<ChatMessageModel> _buildCompletedRunMessages({bool isFinal = true}) {
  return <ChatMessageModel>[
    _assistantMessage(
      id: 'task-1-text',
      text: '最终回答',
      taskId: 'task-1',
      kind: 'text_snapshot',
      seq: 30,
      isFinal: isFinal,
    ),
    _cardMessage(
      id: 'task-1-tool',
      taskId: 'task-1',
      kind: 'tool_completed',
      seq: 20,
      cardData: <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '读取配置文件',
        'summary': '配置读取完成',
      },
    ),
    _thinkingCard(id: 'task-1-thinking', taskId: 'task-1', seq: 10),
    ChatMessageModel.userMessage('用户问题', id: 'user-1'),
  ];
}

ChatMessageModel _assistantMessage({
  required String id,
  required String text,
  required String taskId,
  required String kind,
  required int seq,
  bool? isFinal = false,
}) {
  return ChatMessageModel(
    id: id,
    type: 1,
    user: 2,
    content: <String, dynamic>{'text': text, 'id': id},
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': kind,
      'seq': seq,
      'entryId': id,
      if (isFinal != null) 'isFinal': isFinal,
    },
  );
}

ChatMessageModel _thinkingCard({
  required String id,
  required String taskId,
  required int seq,
}) {
  return _cardMessage(
    id: id,
    taskId: taskId,
    kind: 'thinking_snapshot',
    seq: seq,
    cardData: <String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': '思考过程',
      'stage': 4,
      'isLoading': false,
      'taskID': taskId,
      'cardId': id,
    },
  );
}

ChatMessageModel _cardMessage({
  required String id,
  required String taskId,
  required String kind,
  required int seq,
  required Map<String, dynamic> cardData,
  bool includeStreamMeta = true,
}) {
  return ChatMessageModel.cardMessage(
    cardData,
    id: id,
    streamMeta: includeStreamMeta
        ? <String, dynamic>{
            'parentTaskId': taskId,
            'kind': kind,
            'seq': seq,
            'entryId': id,
            'isFinal': false,
          }
        : null,
  );
}
