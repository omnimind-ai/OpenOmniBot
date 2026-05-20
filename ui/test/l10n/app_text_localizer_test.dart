import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  tearDown(AppTextLocalizer.clearResolvedLocale);

  test('uses active locale override for source-text translations', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(AppTextLocalizer.text('设置'), '设置');

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(AppTextLocalizer.text('设置'), 'Settings');
  });

  test('keeps floating overlay source texts localized after migration', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(AppTextLocalizer.text('当前没有后端任务'), '当前没有后端任务');
    expect(AppTextLocalizer.text('当前没有任何 Agent'), '当前没有任何 Agent');
    expect(
      AppTextLocalizer.text('Agent 后端空闲。轻点打开管理面板。'),
      'Agent 后端空闲。轻点打开管理面板。',
    );

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(AppTextLocalizer.text('当前没有后端任务'), 'No backend tasks running');
    expect(AppTextLocalizer.text('当前没有任何 Agent'), 'No Agents running');
    expect(
      AppTextLocalizer.text('Agent 后端空闲。轻点打开管理面板。'),
      'Agent backend idle. Tap to open manager.',
    );
  });

  test('localizes compact tool stream text', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(AppTextLocalizer.text('已复制工具输出'), '已复制工具输出');
    expect(AppTextLocalizer.text('查看执行记录'), '查看执行记录');
    expect(AppTextLocalizer.text('查看 RunLog'), '查看执行记录');

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(AppTextLocalizer.text('已复制工具输出'), 'Copied tool output');
    expect(AppTextLocalizer.text('查看执行记录'), 'View Run Log');
    expect(AppTextLocalizer.text('查看 RunLog'), 'View Run Log');
  });

  test('resolves process card labels by explicit locale', () {
    expect(
      resolveAgentToolTypeLabel(const <String, dynamic>{
        'toolType': 'research',
      }, locale: const Locale('zh')),
      '网页搜索',
    );
    expect(
      resolveAgentToolTypeLabel(const <String, dynamic>{
        'toolType': 'research',
      }, locale: const Locale('en')),
      'Web search',
    );
    expect(
      resolveAgentToolStatusLabel(const <String, dynamic>{
        'status': 'success',
      }, locale: const Locale('zh')),
      '已完成',
    );
    expect(
      resolveAgentToolStatusLabel(const <String, dynamic>{
        'status': 'success',
      }, locale: const Locale('en')),
      'Done',
    );
    expect(
      resolveAgentToolStatusLabel(const <String, dynamic>{
        'status': 'running',
        'toolType': 'thinking',
      }, locale: const Locale('zh')),
      '思考中',
    );
    expect(
      resolveAgentToolTypeLabel(const <String, dynamic>{
        'toolType': 'vlm',
      }, locale: const Locale('zh')),
      '视觉执行',
    );
    expect(
      resolveAgentToolTypeLabel(const <String, dynamic>{
        'toolType': 'vlm',
      }, locale: const Locale('en')),
      'Visual task',
    );
    expect(
      resolveAgentToolStatusLabel(const <String, dynamic>{
        'status': 'running',
        'toolType': 'vlm',
      }, locale: const Locale('zh')),
      '执行中',
    );
    expect(
      resolveAgentToolTypeLabel(const <String, dynamic>{
        'toolName': 'vlm_task',
        'compile_kind': 'vlm',
      }, locale: const Locale('zh')),
      '视觉执行',
    );
    expect(
      resolveAgentToolStatusLabel(const <String, dynamic>{
        'status': 'running',
        'toolName': 'vlm_task',
        'compile_kind': 'vlm',
      }, locale: const Locale('zh')),
      '执行中',
    );
    expect(
      resolveAgentToolTitle(const <String, dynamic>{
        'toolTitle': 'Tool Call',
      }, locale: const Locale('zh')),
      '工具调用',
    );
    expect(
      resolveAgentToolTitle(const <String, dynamic>{
        'toolType': 'vlm',
        'toolName': 'click',
        'toolTitle': '点击 设置按钮',
        'argsJson': '{"target_description":"设置按钮","x":1,"y":2}',
      }, locale: const Locale('zh')),
      '设置按钮',
    );
  });

  test('keeps server-provided process text untranslated', () {
    expect(
      resolveAgentToolTitle(const <String, dynamic>{
        'toolTitle': '设置',
      }, locale: const Locale('en')),
      '设置',
    );
    expect(
      resolveAgentToolPreview(const <String, dynamic>{
        'toolTitle': 'Search result',
        'summary': 'https://example.com/设置',
      }, locale: const Locale('en')),
      'https://example.com/设置',
    );
  });

  test('thinking messages are not extracted as tool cards', () {
    final cards = extractAgentToolCards([
      ChatMessageModel.cardMessage(const <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': '先分析输入',
        'stage': 1,
        'isLoading': true,
        'taskID': 'task-thinking',
        'cardId': 'task-thinking-card',
      }, id: 'thinking-running'),
    ]);

    expect(cards, isEmpty);
  });
}
