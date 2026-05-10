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

  test('keeps in-flight task ungrouped while task is active', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: false),
      activeTaskIds: const <String>{'task-1'},
    );

    expect(entries, hasLength(4));
    expect(entries.where((entry) => entry.group != null), isEmpty);
  });

  test('keeps final text snapshot ungrouped until task becomes inactive', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: true),
      activeTaskIds: const <String>{'task-1'},
    );

    expect(entries, hasLength(4));
    expect(entries.where((entry) => entry.group != null), isEmpty);
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
}) {
  return ChatMessageModel.cardMessage(
    cardData,
    id: id,
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': kind,
      'seq': seq,
      'entryId': id,
      'isFinal': false,
    },
  );
}
