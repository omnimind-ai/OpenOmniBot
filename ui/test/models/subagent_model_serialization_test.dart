import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/scheduled_task.dart';

void main() {
  test('ConversationMode supports chat_only serialization', () {
    expect(ConversationMode.chatOnly.storageValue, 'chat_only');
    expect(
      ConversationMode.fromStorageValue('chat_only'),
      ConversationMode.chatOnly,
    );
    expect(
      ConversationMode.fromStorageValue('CHAT_ONLY'),
      ConversationMode.chatOnly,
    );
  });

  test('ConversationMode supports subagent serialization', () {
    expect(ConversationMode.subagent.storageValue, 'subagent');
    expect(
      ConversationMode.fromStorageValue('subagent'),
      ConversationMode.subagent,
    );
    expect(
      ConversationMode.fromStorageValue('SUBAGENT'),
      ConversationMode.subagent,
    );
  });

  test('ScheduledTask keeps subagent fields through json', () {
    final now = DateTime.now().millisecondsSinceEpoch;
    final task = ScheduledTask(
      id: 'task-subagent-1',
      title: '新闻整理',
      packageName: '',
      nodeId: 'n1',
      suggestionId: 's1',
      targetKind: 'subagent',
      subagentConversationId: '12345',
      parentConversationId: '7',
      parentConversationMode: ConversationMode.normal.storageValue,
      subagentPrompt: '每晚整理一下今天的新闻',
      notificationEnabled: true,
      type: ScheduledTaskType.fixedTime,
      fixedTime: '18:00',
      repeatDaily: true,
      isEnabled: true,
      createdAt: now,
      nextExecutionTime: now + 3600 * 1000,
      suggestionData: const {
        'targetKind': 'subagent',
        'subagentPrompt': '每晚整理一下今天的新闻',
      },
    );

    final restored = ScheduledTask.fromJson(task.toJson());

    expect(restored.targetKind, 'subagent');
    expect(restored.subagentConversationId, '12345');
    expect(restored.parentConversationId, '7');
    expect(
      restored.parentConversationMode,
      ConversationMode.normal.storageValue,
    );
    expect(restored.subagentPrompt, '每晚整理一下今天的新闻');
    expect(restored.notificationEnabled, true);
    expect(restored.suggestionData?['targetKind'], 'subagent');
  });
}
