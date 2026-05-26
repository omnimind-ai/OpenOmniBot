import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_tool_activity_card.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/deep_thinking_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/streaming_text.dart';

const String _kThinkingDetailText = '详细思考过程';
const String _kThinkingFixtureText = '思考摘要\n$_kThinkingDetailText';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getSceneModelBindings') {
            return const <Map<String, dynamic>>[];
          }
          if (call.method == 'getSceneVoiceConfig') {
            return const <String, dynamic>{
              'autoPlay': false,
              'voiceId': 'default_zh',
              'stylePreset': '默认',
              'customStyle': '',
            };
          }
          if (call.method == 'getInternalRunLogTimeline') {
            return <String, dynamic>{
              'success': true,
              'run_id': (call.arguments as Map?)?['runId'] ?? '',
              'done_reason': 'finished',
              'cards': const <Map<String, dynamic>>[],
            };
          }
          return 'SUCCESS';
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('empty chat state follows main greeting layout', (tester) async {
    await tester.pumpWidget(
      _buildLocalizedApp(
        child: ChatMessageList(
          messages: const [],
          scrollController: ScrollController(),
          bottomOverlayInset: 128,
          onBeforeTaskExecute: () async {},
        ),
      ),
    );

    await tester.pump();

    expect(find.byType(AnimatedPadding), findsNothing);
    expect(find.byType(ChatEmptyGreeting), findsOneWidget);
  });

  testWidgets(
    'parent handoff keeps list away from latest on follow-up frames',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildMessagesWithThinkingCard();

      await tester.pumpWidget(
        _buildChatMessageListHarness(
          controller: controller,
          messages: messages,
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      final deepThinkingCard = find.descendant(
        of: find.byType(ChatMessageList),
        matching: find.byType(DeepThinkingCard),
      );
      expect(deepThinkingCard, findsOneWidget);

      await tester.tap(
        find.descendant(of: deepThinkingCard, matching: find.byType(InkWell)),
      );
      await tester.pumpAndSettle();

      final dragStart =
          tester.getTopLeft(deepThinkingCard) + const Offset(120, 96);
      await tester.dragFrom(dragStart, const Offset(0, 60));
      await tester.pump();

      final movedOffset = controller.offset;
      expect(movedOffset, lessThan(controller.position.maxScrollExtent - 48));

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pumpAndSettle();

      expect(controller.offset, closeTo(movedOffset, 1));
    },
  );

  testWidgets('list resumes auto-follow after layout returns it to latest', (
    tester,
  ) async {
    final controller = ScrollController();
    var messages = _buildMessagesWithThinkingCard();
    late StateSetter setState;

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: StatefulBuilder(
          builder: (context, stateSetter) {
            setState = stateSetter;
            return SizedBox(
              width: 400,
              height: 520,
              child: ChatMessageList(
                messages: messages,
                scrollController: controller,
                onBeforeTaskExecute: () async {},
              ),
            );
          },
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 32));

    final deepThinkingCard = find.descendant(
      of: find.byType(ChatMessageList),
      matching: find.byType(DeepThinkingCard),
    );
    await tester.tap(
      find.descendant(of: deepThinkingCard, matching: find.byType(InkWell)),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 32));

    final dragStart =
        tester.getTopLeft(deepThinkingCard) + const Offset(120, 96);
    await tester.dragFrom(dragStart, const Offset(0, 60));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 32));

    expect(controller.offset, lessThan(controller.position.maxScrollExtent));

    setState(() {
      messages = <ChatMessageModel>[
        messages.first,
        ...messages.skip(1).take(1),
      ];
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));

    expect(controller.offset, closeTo(controller.position.maxScrollExtent, 1));

    setState(() {
      messages = <ChatMessageModel>[
        ChatMessageModel.assistantMessage('新的最新消息', id: 'new-latest'),
        ...messages,
      ];
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));

    expect(controller.offset, closeTo(controller.position.maxScrollExtent, 1));
  });

  testWidgets(
    'small manual drag away from latest disables follow-up auto stick',
    (tester) async {
      final controller = ScrollController();
      var messages = _buildSimpleAssistantMessages(20, prefix: '初始消息');
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 520,
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      await tester.drag(find.byType(ListView), const Offset(0, 36));
      await tester.pumpAndSettle();

      final movedOffset = controller.offset;
      expect(movedOffset, lessThan(controller.position.maxScrollExtent));
      expect(
        movedOffset,
        greaterThan(controller.position.maxScrollExtent - 48),
      );

      setState(() {
        messages = <ChatMessageModel>[
          ChatMessageModel.assistantMessage('新的最新消息', id: 'new-latest'),
          ...messages,
        ];
      });
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(movedOffset, 2),
        reason: 'A small manual drag away from latest should not snap back.',
      );
      expect(controller.offset, lessThan(controller.position.maxScrollExtent));
    },
  );

  testWidgets('latest user message no longer shows inline edit button', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = <ChatMessageModel>[
      ChatMessageModel.userMessage('最新用户消息', id: 'latest-user'),
      ChatMessageModel.assistantMessage('收到', id: 'assistant-1'),
      ChatMessageModel.userMessage('更早的用户消息', id: 'older-user'),
    ];

    await tester.pumpWidget(
      _buildChatMessageListHarness(controller: controller, messages: messages),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));

    final latestBubble = find.byKey(
      const ValueKey('user-message-bubble-latest-user'),
    );

    expect(latestBubble, findsOneWidget);
    expect(
      find.descendant(of: latestBubble, matching: find.byType(IconButton)),
      findsNothing,
    );
    expect(find.byIcon(Icons.edit_outlined), findsNothing);
  });

  testWidgets('latest user message editing reuses bubble content area', (
    tester,
  ) async {
    final controller = ScrollController();
    final editingController = TextEditingController(text: '最新用户消息');
    final messages = <ChatMessageModel>[
      ChatMessageModel.userMessage('最新用户消息', id: 'latest-user'),
      ChatMessageModel.assistantMessage('收到', id: 'assistant-1'),
    ];

    addTearDown(editingController.dispose);

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            editingUserMessageId: 'latest-user',
            userMessageEditController: editingController,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 32));

    expect(
      find.byKey(const ValueKey('user-message-bubble-latest-user')),
      findsOneWidget,
    );
    expect(find.byType(TextField), findsOneWidget);
    expect(find.text('取消'), findsOneWidget);
    expect(find.text('保存并发送'), findsOneWidget);
    expect(find.byIcon(Icons.edit_outlined), findsNothing);
  });

  testWidgets(
    'shared message scroll controller does not crash during long-message rebuilds',
    (tester) async {
      final controller = ScrollController();
      final messages = <ChatMessageModel>[
        ChatMessageModel.assistantMessage(
          List.generate(
            120,
            (index) => '超长消息第 ${index + 1} 行，用于复现多滚动位置场景。',
          ).join('\n'),
          id: 'long-message',
        ),
      ];

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: Column(
            children: [
              Expanded(
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              ),
              Expanded(
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              ),
            ],
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      expect(controller.positions.length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'shared message scroll controller stays safe with deep thinking cards',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildMessagesWithThinkingCard();

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 960,
            child: Column(
              children: [
                Expanded(
                  child: ChatMessageList(
                    messages: messages,
                    scrollController: controller,
                    onBeforeTaskExecute: () async {},
                  ),
                ),
                Expanded(
                  child: ChatMessageList(
                    messages: messages,
                    scrollController: controller,
                    onBeforeTaskExecute: () async {},
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      expect(controller.positions.length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'streaming deep thinking updates keep the message list pinned to latest',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()
        ..replaceAllMessages(_buildStreamingThinkingMessages(thinkingLines: 1));

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      messages[0] = ChatMessageModel.cardMessage(<String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': List.generate(
          40,
          (index) => '第 ${index + 1} 行流式思考内容，验证列表持续跟随最新位置。',
        ).join('\n'),
        'stage': 1,
        'isLoading': true,
        'isCollapsible': true,
        'taskID': 'streaming-thinking-card',
      }, id: 'streaming-thinking-card');

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pump(const Duration(milliseconds: 16));

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'observable agent text updates rebuild the visible streaming bubble',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()
        ..replaceAllMessages([
          ChatMessageModel(
            id: 'agent-task-text',
            type: 1,
            user: 2,
            content: {
              'text': '第一段回复',
              'id': 'agent-task-text',
              'renderMarkdown': true,
            },
            streamMeta: const {
              'parentTaskId': 'agent-task',
              'kind': 'text_snapshot',
              'seq': 1,
              'isFinal': false,
            },
          ),
        ]);

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              activeAgentTaskIds: const {'agent-task'},
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pump();

      expect(
        tester.widget<StreamingText>(find.byType(StreamingText)).fullText,
        '第一段回复',
      );

      final existing = messages[0];
      final content = Map<String, dynamic>.from(existing.content ?? const {});
      content['text'] = '第一段回复\n第二段已经流式到达';
      messages[0] = existing.copyWith(
        content: content,
        streamMeta: const {
          'parentTaskId': 'agent-task',
          'kind': 'text_snapshot',
          'seq': 2,
          'isFinal': false,
        },
      );

      await tester.pump();

      expect(
        tester.widget<StreamingText>(find.byType(StreamingText)).fullText,
        '第一段回复\n第二段已经流式到达',
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'expanding an older thinking card does not snap the list back to latest',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildToggleRegressionThinkingMessages();

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      final inkWells = find.byType(InkWell);
      expect(inkWells, findsNWidgets(2));

      final offsetBefore = controller.offset;
      final maxBefore = controller.position.maxScrollExtent;

      await tester.tap(inkWells.first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 220));
      await tester.pumpAndSettle();

      expect(controller.position.maxScrollExtent, greaterThan(maxBefore + 40));
      expect(controller.offset, closeTo(offsetBefore, 8));
      expect(
        controller.offset,
        lessThan(controller.position.maxScrollExtent - 40),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('chat history no longer uses pull-to-refresh wrapper', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildSimpleAssistantMessages(24, prefix: '刷新机制移除');

    await tester.pumpWidget(
      _buildChatMessageListHarness(controller: controller, messages: messages),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));

    expect(find.byType(RefreshIndicator), findsNothing);
  });

  testWidgets('completed agent run collapses to summary and final answer', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('已完成'), findsOneWidget);
    expect(find.textContaining('2 步'), findsOneWidget);
    expect(find.text('最终回答'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('agent-run-avatar-task-1')),
      findsOneWidget,
    );
    expect(find.text('运行 git status'), findsNothing);
    expect(_thinkingDetailFinder(), findsNothing);

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-1')));
    await tester.pump();
    expect(
      find.byKey(const ValueKey('agent-run-process-task-1')),
      findsOneWidget,
    );
    expect(find.text('思考'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 120));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('运行 git status'), findsOneWidget);
    expect(_thinkingDetailFinder(), findsOneWidget);
    expect(find.byType(AgentAvatarCircle), findsOneWidget);
    expect(find.byType(AgentAvatarButton), findsNothing);
  });

  testWidgets('agent run summary stays stable on narrow width', (tester) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 220,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('最终回答'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('agent run summary localizes in English', (tester) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('en'),
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('Steps'), findsOneWidget);
    expect(find.text('Done'), findsOneWidget);
    expect(find.textContaining('2 steps'), findsOneWidget);
    expect(find.textContaining('1 thought'), findsNothing);
    expect(find.textContaining('1 tool'), findsNothing);
    expect(find.textContaining('1 thoughts'), findsNothing);
    expect(find.textContaining('1 tools'), findsNothing);
    expect(find.text('步骤'), findsNothing);
    expect(find.text('已完成'), findsNothing);
  });

  testWidgets('text-only agent run keeps compact runlog entry', (tester) async {
    final controller = ScrollController();
    final messages = _buildTextOnlyAgentRunMessages(runLogId: null);

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('无可展开步骤'), findsOneWidget);
    expect(find.text('已记录'), findsNothing);
    expect(find.text('运行记录'), findsNothing);
    expect(find.text('直接回答'), findsOneWidget);
    expect(find.byIcon(Icons.keyboard_arrow_down_rounded), findsNothing);
    expect(find.byIcon(Icons.route_rounded), findsOneWidget);
    expect(
      find.byKey(const ValueKey('agent-run-process-task-text-only')),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-text-only')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(
      find.byKey(const ValueKey('agent-run-process-task-text-only')),
      findsNothing,
    );
    expect(find.text('直接回答'), findsOneWidget);
    expect(find.text('暂无步骤数据'), findsOneWidget);
    expect(find.text('RunLog 已记录'), findsNothing);
    expect(find.textContaining('没有工具调用'), findsOneWidget);
  });

  testWidgets('text-only runlog entry localizes in English', (tester) async {
    final controller = ScrollController();
    final messages = _buildTextOnlyAgentRunMessages(runLogId: null);

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('en'),
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('Steps'), findsOneWidget);
    expect(find.text('No steps'), findsOneWidget);
    expect(find.text('Logged'), findsNothing);
    expect(find.text('RunLog'), findsNothing);
    expect(find.text('运行记录'), findsNothing);
    expect(find.text('已记录'), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-text-only')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('No step data'), findsOneWidget);
    expect(find.text('暂无步骤数据'), findsNothing);
    expect(find.text('RunLog logged'), findsNothing);
  });

  testWidgets('text-only runlog entry stays stable on narrow width', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildTextOnlyAgentRunMessages(runLogId: null);

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('en'),
        child: SizedBox(
          width: 180,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('Steps'), findsOneWidget);
    expect(find.text('No steps'), findsOneWidget);
    expect(find.text('Logged'), findsNothing);
    expect(find.text('RunLog'), findsNothing);
    expect(find.byIcon(Icons.keyboard_arrow_down_rounded), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('reopening run expands thinking details from summary again', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final summaryToggle = find.byKey(
      const ValueKey('agent-run-summary-task-1'),
    );
    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(_thinkingDetailFinder(), findsOneWidget);

    await tester.tap(find.text('思考'));
    await tester.pumpAndSettle();
    expect(_thinkingDetailFinder(), findsNothing);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(_thinkingDetailFinder(), findsNothing);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(find.text('思考'), findsOneWidget);
    expect(_thinkingDetailFinder(), findsOneWidget);
  });

  testWidgets('single compacted browser process opens detail from header', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildBrowserActivityAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-2')));
    await tester.pumpAndSettle();

    expect(find.byKey(kAgentToolDetailSheetKey), findsOneWidget);
    expect(find.byType(AgentToolActivityCard), findsNothing);
    expect(find.text('点击登录按钮'), findsOneWidget);
  });

  testWidgets('process rows localize in Chinese and stay compact', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildMixedProcessRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 560,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-mixed')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('思考中'), findsOneWidget);
    expect(find.text('网页搜索'), findsOneWidget);
    expect(find.text('工具调用'), findsOneWidget);
    expect(_thinkingDetailFinder(), findsOneWidget);
    expect(find.text('Thinking'), findsNothing);
    expect(find.text('Web search'), findsNothing);
    expect(find.text('Tool activity'), findsNothing);

    final researchSurface = find.byKey(
      const ValueKey(
        'agent-tool-activity-compact-surface-task-mixed-research-activity',
      ),
    );
    expect(researchSurface, findsOneWidget);
    expect(tester.getSize(researchSurface).width, lessThan(400 * 0.9));
    _expectUniformProcessText(tester, researchSurface);
    expect(tester.takeException(), isNull);
  });

  testWidgets('repeated generic process activities keep unique row keys', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildRepeatedGenericProcessRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 620,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-repeated')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(
      find.byKey(const ValueKey('agent-run-process-task-repeated')),
      findsOneWidget,
    );
    expect(find.byType(AgentToolActivityCard), findsNWidgets(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('process rows localize in English', (tester) async {
    final controller = ScrollController();
    final messages = _buildMixedProcessRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        locale: const Locale('en'),
        child: SizedBox(
          width: 400,
          height: 560,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-mixed')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('Thinking'), findsOneWidget);
    expect(find.text('Web search'), findsOneWidget);
    expect(find.text('Tool call'), findsOneWidget);
    expect(find.text('思考中'), findsNothing);
    expect(find.text('网页搜索'), findsNothing);
    expect(find.text('工具调用'), findsNothing);
  });

  testWidgets('VLM process row uses visual task label and compact width', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildVlmProcessRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 560,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-vlm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('视觉执行'), findsOneWidget);
    expect(find.text('网页搜索'), findsNothing);
    expect(find.text('Visual task'), findsNothing);

    expect(find.byType(AgentToolActivityCard), findsOneWidget);
    final vlmSurface = find.byKey(
      const ValueKey(
        'agent-tool-activity-compact-surface-task-vlm-vlm-activity',
      ),
    );
    expect(vlmSurface, findsOneWidget);
    expect(tester.getSize(vlmSurface).width, lessThan(400 * 0.9));
    _expectUniformProcessText(tester, vlmSurface);

    expect(find.text('2 步'), findsOneWidget);
    expect(find.textContaining('设置按钮'), findsNothing);

    await tester.tap(vlmSurface);
    await tester.pumpAndSettle();
    expect(find.text('暂无步骤数据'), findsOneWidget);
    expect(find.textContaining('没有工具调用'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('multi-step VLM activity stays collapsed and opens full runlog', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildVlmProcessRunMessages(includeThinking: false);

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 560,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-vlm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.byType(AgentToolActivityCard), findsOneWidget);
    expect(find.text('视觉执行'), findsOneWidget);
    expect(find.text('2 步'), findsOneWidget);
    expect(find.textContaining('设置按钮'), findsNothing);
    expect(find.text('工具调用历史'), findsNothing);

    await tester.tap(
      find.byKey(
        const ValueKey(
          'agent-tool-activity-compact-surface-task-vlm-vlm-activity',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('暂无步骤数据'), findsOneWidget);
    expect(find.textContaining('没有工具调用'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('multi-step VLM card opens full RunLog from one click', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildVlmProcessRunMessages(includeThinking: false);

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 560,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-vlm')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.byType(AgentToolActivityCard), findsOneWidget);
    expect(find.text('查看完整 RunLog'), findsNothing);
    expect(find.textContaining('设置按钮'), findsNothing);

    final surface = find.byKey(
      const ValueKey(
        'agent-tool-activity-compact-surface-task-vlm-vlm-activity',
      ),
    );
    await tester.tap(surface);
    await tester.pumpAndSettle();

    expect(find.text('暂无步骤数据'), findsOneWidget);
    expect(find.textContaining('没有工具调用'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('VLM run header exposes full runlog without expanding steps', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildVlmProcessRunMessages(includeThinking: false);

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 560,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('agent-run-runlog-task-vlm')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('agent-run-process-task-vlm')),
      findsNothing,
    );

    await tester.tap(find.byKey(const ValueKey('agent-run-runlog-task-vlm')));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('agent-run-process-task-vlm')),
      findsNothing,
    );
    expect(find.text('暂无步骤数据'), findsOneWidget);
    expect(find.textContaining('没有工具调用'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('single thinking process opens expanded detail from header', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildSingleThinkingAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('思考摘要'), findsOneWidget);
    expect(_thinkingDetailFinder(), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-thinking-only')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(
      find.byKey(const ValueKey('agent-run-process-task-thinking-only')),
      findsOneWidget,
    );
    expect(_thinkingDetailFinder(), findsOneWidget);
    final detailText = tester.widget<Text>(_thinkingDetailFinder());
    expect(detailText.style?.fontFamily, 'monospace');
    expect(detailText.style?.fontStyle, FontStyle.italic);
    expect(detailText.style?.fontWeight, FontWeight.w500);
    expect(detailText.style?.fontSize, 11);
    expect(detailText.style?.letterSpacing ?? 0, 0);
  });

  testWidgets('single tool process opens detail sheet from header', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildSingleToolAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('agent-run-summary-task-tool-only')),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(kAgentToolDetailSheetKey), findsOneWidget);
    expect(
      find.byKey(const ValueKey('agent-run-process-task-tool-only')),
      findsNothing,
    );
  });

  testWidgets('agent run expansion can be controlled by the parent page', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();
    Set<String> expandedTaskIds = <String>{};
    late StateSetter setState;

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: StatefulBuilder(
          builder: (context, stateSetter) {
            setState = stateSetter;
            return SizedBox(
              width: 400,
              height: 520,
              child: ChatMessageList(
                messages: messages,
                scrollController: controller,
                expandedAgentRunTaskIds: expandedTaskIds,
                onExpandedAgentRunTaskIdsChanged: (nextTaskIds) {
                  setState(() {
                    expandedTaskIds = nextTaskIds;
                  });
                },
                onBeforeTaskExecute: () async {},
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    final summaryToggle = find.byKey(
      const ValueKey('agent-run-summary-task-1'),
    );
    expect(find.text('运行 git status'), findsNothing);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(expandedTaskIds, const {'task-1'});
    expect(find.text('运行 git status'), findsOneWidget);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(expandedTaskIds, isEmpty);
    expect(find.text('运行 git status'), findsNothing);
  });

  testWidgets(
    'cancelled agent run auto-collapses trace and shows cancel body',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()
        ..replaceAllMessages(_buildCompletedAgentRunMessages());
      Set<String> expandedTaskIds = <String>{'task-1'};
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 520,
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  expandedAgentRunTaskIds: expandedTaskIds,
                  onExpandedAgentRunTaskIdsChanged: (nextTaskIds) {
                    setState(() {
                      expandedTaskIds = nextTaskIds;
                    });
                  },
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('运行 git status'), findsOneWidget);

      messages.insert(
        0,
        ChatMessageModel(
          id: 'task-1-cancelled',
          type: 1,
          user: 2,
          content: const <String, dynamic>{
            'text': '任务已取消',
            'id': 'task-1-cancelled',
            'renderMarkdown': false,
          },
          streamMeta: const <String, dynamic>{
            'parentTaskId': 'task-1',
            'kind': 'text_snapshot',
            'seq': 1000000000,
            'entryId': 'task-1-cancelled',
            'isFinal': true,
          },
        ),
      );
      await tester.pumpAndSettle();

      expect(expandedTaskIds, isEmpty);
      expect(_streamingTextFinder('任务已取消'), findsOneWidget);
      expect(find.text('运行 git status'), findsNothing);
    },
  );

  testWidgets(
    'expanding latest agent run keeps the summary row anchored while inset grows',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildCompletedAgentRunMessages();
      Set<String> expandedTaskIds = <String>{};
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 220,
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  expandedAgentRunTaskIds: expandedTaskIds,
                  onExpandedAgentRunTaskIdsChanged: (nextTaskIds) {
                    setState(() {
                      expandedTaskIds = nextTaskIds;
                    });
                  },
                  bottomOverlayInset: expandedTaskIds.isEmpty ? 0 : 96,
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      final summaryToggle = find.byKey(
        const ValueKey('agent-run-summary-task-1'),
      );
      final initialTop = tester.getTopLeft(summaryToggle).dy;
      final initialOffset = controller.offset;

      await tester.tap(summaryToggle);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 120));

      final midAnimationTop = tester.getTopLeft(summaryToggle).dy;
      expect(midAnimationTop, closeTo(initialTop, 4));
      expect(controller.offset, closeTo(initialOffset, 4));
      expect(
        controller.offset,
        lessThan(controller.position.maxScrollExtent - 24),
      );
    },
  );

  testWidgets('active agent run keeps process collapsed by default', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildActiveAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            activeAgentTaskIds: const <String>{'task-1'},
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 32));

    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('进行中'), findsOneWidget);
    expect(find.byType(DeepThinkingCard), findsNothing);
    expect(
      find.byKey(const ValueKey('agent-run-process-task-1')),
      findsNothing,
    );
    expect(find.text('最终回答'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-1')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('思考中'), findsOneWidget);
    expect(find.text('运行 git status'), findsOneWidget);
  });

  testWidgets('completed active run keeps tool output visible', (tester) async {
    final controller = ScrollController();
    var messages = _buildActiveAgentRunMessages();
    var activeTaskIds = const <String>{'task-1'};

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            activeAgentTaskIds: activeTaskIds,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));

    messages = _buildCompletedAgentRunMessages();
    activeTaskIds = const <String>{};
    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            activeAgentTaskIds: activeTaskIds,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('已完成'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('agent-run-process-task-1')),
      findsNothing,
    );
    expect(find.text('最终回答'), findsOneWidget);
    expect(_thinkingDetailFinder(), findsNothing);
  });

  testWidgets('reaching top auto-loads older messages without jumping to top', (
    tester,
  ) async {
    final controller = ScrollController();
    var messages = _buildSimpleAssistantMessages(20, prefix: '初始消息');
    var loadMoreCalls = 0;
    late StateSetter setState;

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: StatefulBuilder(
          builder: (context, stateSetter) {
            setState = stateSetter;
            return SizedBox(
              width: 400,
              height: 520,
              child: ChatMessageList(
                messages: messages,
                scrollController: controller,
                hasMore: loadMoreCalls == 0,
                onLoadMore: () async {
                  loadMoreCalls += 1;
                  setState(() {
                    messages = <ChatMessageModel>[
                      ...messages,
                      ..._buildSimpleAssistantMessages(
                        8,
                        prefix: '更早消息',
                        idPrefix: 'older',
                        startIndex: messages.length,
                      ),
                    ];
                  });
                },
                onBeforeTaskExecute: () async {},
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    controller.jumpTo(24);
    await tester.pump();

    await tester.drag(find.byType(ListView), const Offset(0, 120));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pumpAndSettle();

    expect(loadMoreCalls, 1);
    expect(messages.length, 28);
    expect(controller.offset, greaterThan(24));
    expect(tester.takeException(), isNull);
  });
}

Widget _buildChatMessageListHarness({
  required ScrollController controller,
  required List<ChatMessageModel> messages,
}) {
  return _buildLocalizedApp(
    child: SizedBox(
      width: 400,
      height: 520,
      child: ChatMessageList(
        messages: messages,
        scrollController: controller,
        onBeforeTaskExecute: () async {},
      ),
    ),
  );
}

Widget _buildLocalizedApp({
  required Widget child,
  Locale locale = const Locale('zh'),
}) {
  return MaterialApp(
    locale: locale,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(body: child),
  );
}

Finder _thinkingDetailFinder() => find.textContaining(_kThinkingDetailText);

void _expectUniformProcessText(WidgetTester tester, Finder surface) {
  final textWidgets = find
      .descendant(of: surface, matching: find.byType(Text))
      .evaluate()
      .map((element) => element.widget)
      .whereType<Text>()
      .toList(growable: false);
  expect(textWidgets, isNotEmpty);
  for (final widget in textWidgets) {
    expect(widget.style?.fontSize, 12);
    expect(widget.style?.letterSpacing ?? 0, 0);
  }
}

Finder _streamingTextFinder(String fullText) {
  return find.byWidgetPredicate(
    (widget) => widget is StreamingText && widget.fullText == fullText,
    description: 'StreamingText("$fullText")',
  );
}

List<ChatMessageModel> _buildMessagesWithThinkingCard() {
  return [
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        80,
        (index) => '第 ${index + 1} 行思考内容，供消息列表滚动回归测试使用。',
      ).join('\n'),
      'stage': 4,
      'isLoading': false,
      'isCollapsible': true,
      'taskID': 'thinking-card',
    }, id: 'thinking-card'),
    ...List.generate(12, (index) {
      return ChatMessageModel.assistantMessage(
        List.generate(
          4,
          (line) => '较早消息 ${index + 1} - 第 ${line + 1} 行',
        ).join('\n'),
        id: 'older-$index',
      );
    }),
  ];
}

List<ChatMessageModel> _buildSimpleAssistantMessages(
  int count, {
  required String prefix,
  String idPrefix = 'assistant',
  int startIndex = 0,
}) {
  return List<ChatMessageModel>.generate(count, (index) {
    final resolvedIndex = startIndex + index;
    return ChatMessageModel.assistantMessage(
      List.generate(
        3,
        (line) => '$prefix ${resolvedIndex + 1} - 第 ${line + 1} 行内容，用于分页加载测试。',
      ).join('\n'),
      id: '$idPrefix-$resolvedIndex',
    );
  });
}

List<ChatMessageModel> _buildStreamingThinkingMessages({
  required int thinkingLines,
}) {
  return <ChatMessageModel>[
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        thinkingLines,
        (index) => '第 ${index + 1} 行流式思考内容，验证列表持续跟随最新位置。',
      ).join('\n'),
      'stage': 1,
      'isLoading': true,
      'isCollapsible': true,
      'taskID': 'streaming-thinking-card',
    }, id: 'streaming-thinking-card'),
    ...List.generate(18, (index) {
      return ChatMessageModel.assistantMessage(
        List.generate(
          5,
          (line) => '较早消息 ${index + 1} - 第 ${line + 1} 行',
        ).join('\n'),
        id: 'streaming-older-$index',
      );
    }),
  ];
}

List<ChatMessageModel> _buildToggleRegressionThinkingMessages() {
  return <ChatMessageModel>[
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        3,
        (index) => '最新思考卡第 ${index + 1} 行，保持可见。',
      ).join('\n'),
      'stage': 4,
      'isLoading': false,
      'isCollapsible': true,
      'taskID': 'latest-thinking-card',
    }, id: 'latest-thinking-card'),
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        60,
        (index) => '较早思考卡第 ${index + 1} 行，展开后高度明显增加。',
      ).join('\n'),
      'stage': 4,
      'isLoading': false,
      'isCollapsible': true,
      'taskID': 'older-thinking-card',
    }, id: 'older-thinking-card'),
    ...List.generate(6, (index) {
      return ChatMessageModel.assistantMessage(
        List.generate(
          3,
          (line) => '普通消息 ${index + 1} - 第 ${line + 1} 行',
        ).join('\n'),
        id: 'toggle-regression-$index',
      );
    }),
  ];
}

List<ChatMessageModel> _buildCompletedAgentRunMessages({bool isFinal = true}) {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '最终回答', 'id': 'task-1-text'},
      streamMeta: <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-1-text',
        'isFinal': isFinal,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'terminal',
        'toolTitle': '运行 git status',
        'summary': '命令执行完成',
        'terminalOutput': 'On branch main',
      },
      id: 'task-1-tool',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_completed',
        'seq': 20,
        'entryId': 'task-1-tool',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': _kThinkingFixtureText,
        'stage': 4,
        'isLoading': false,
        'taskID': 'task-1',
        'cardId': 'task-1-thinking',
      },
      id: 'task-1-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}

List<ChatMessageModel> _buildTextOnlyAgentRunMessages({String? runLogId}) {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-text-only-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{
        'text': '直接回答',
        'id': 'task-text-only-text',
      },
      streamMeta: <String, dynamic>{
        'parentTaskId': 'task-text-only',
        'kind': 'text_snapshot',
        'seq': 10,
        'entryId': 'task-text-only-text',
        'isFinal': true,
        if (runLogId != null) 'runLogId': runLogId,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-text-only-user'),
  ];
}

List<ChatMessageModel> _buildBrowserActivityAgentRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-2-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '页面已打开', 'id': 'task-2-text'},
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-2',
        'kind': 'text_snapshot',
        'seq': 40,
        'entryId': 'task-2-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'browser',
        'toolName': 'browser_use',
        'toolTitle': '点击登录按钮',
        'argsJson': '{"action":"click","selector":"#login"}',
      },
      id: 'task-2-browser-click',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-2',
        'kind': 'tool_completed',
        'seq': 30,
        'entryId': 'task-2-browser-click',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'browser',
        'toolName': 'browser_use',
        'toolTitle': '打开 example.com',
        'argsJson': '{"action":"navigate","url":"https://example.com"}',
      },
      id: 'task-2-browser-navigate',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-2',
        'kind': 'tool_completed',
        'seq': 20,
        'entryId': 'task-2-browser-navigate',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('打开网页', id: 'task-2-user'),
  ];
}

List<ChatMessageModel> _buildMixedProcessRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-mixed-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '最终回答', 'id': 'task-mixed-text'},
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-mixed',
        'kind': 'text_snapshot',
        'seq': 50,
        'entryId': 'task-mixed-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'tool',
        'toolName': 'custom_tool',
        'summary': '工具完成',
        'argsJson': '{"query":"本地任务"}',
      },
      id: 'task-mixed-generic',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-mixed',
        'kind': 'tool_completed',
        'seq': 40,
        'entryId': 'task-mixed-generic',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'research',
        'toolName': 'web_search',
        'summary': '搜索完成',
        'argsJson': '{"query":"今日天气"}',
      },
      id: 'task-mixed-research',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-mixed',
        'kind': 'tool_completed',
        'seq': 30,
        'entryId': 'task-mixed-research',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': _kThinkingFixtureText,
        'stage': 1,
        'isLoading': true,
        'taskID': 'task-mixed',
        'cardId': 'task-mixed-thinking',
      },
      id: 'task-mixed-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-mixed',
        'kind': 'thinking_snapshot',
        'seq': 20,
        'entryId': 'task-mixed-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-mixed-user'),
  ];
}

List<ChatMessageModel> _buildRepeatedGenericProcessRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-repeated-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{
        'text': '最终回答',
        'id': 'task-repeated-text',
      },
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-repeated',
        'kind': 'text_snapshot',
        'seq': 50,
        'entryId': 'task-repeated-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'tool',
        'toolName': 'custom_tool',
        'summary': '第二次工具完成',
        'argsJson': '{"query":"second"}',
      },
      id: 'task-repeated-tool-2',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-repeated',
        'kind': 'tool_completed',
        'seq': 40,
        'entryId': 'task-repeated-tool-2',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': _kThinkingFixtureText,
        'stage': 4,
        'isLoading': false,
        'taskID': 'task-repeated',
        'cardId': 'task-repeated-thinking',
      },
      id: 'task-repeated-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-repeated',
        'kind': 'thinking_snapshot',
        'seq': 30,
        'entryId': 'task-repeated-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'tool',
        'toolName': 'custom_tool',
        'summary': '第一次工具完成',
        'argsJson': '{"query":"first"}',
      },
      id: 'task-repeated-tool-1',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-repeated',
        'kind': 'tool_completed',
        'seq': 20,
        'entryId': 'task-repeated-tool-1',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-repeated-user'),
  ];
}

List<ChatMessageModel> _buildVlmProcessRunMessages({
  bool includeThinking = true,
}) {
  final messages = <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-vlm-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '已完成', 'id': 'task-vlm-text'},
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-vlm',
        'kind': 'text_snapshot',
        'seq': 40,
        'entryId': 'task-vlm-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'vlm',
        'toolName': 'type',
        'toolTitle': '输入文本',
        'summary': '输入 hello',
        'runLogId': 'task-vlm',
        'argsJson': '{"content":"hello"}',
      },
      id: 'task-vlm-step-2',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-vlm',
        'kind': 'tool_completed',
        'seq': 35,
        'entryId': 'task-vlm-step-2',
        'runLogId': 'task-vlm',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'vlm',
        'toolName': 'click',
        'toolTitle': '点击 设置按钮',
        'summary': '点击 设置按钮',
        'runLogId': 'task-vlm',
        'argsJson': '{"target_description":"设置按钮","x":120,"y":240}',
      },
      id: 'task-vlm-step-1',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-vlm',
        'kind': 'tool_completed',
        'seq': 30,
        'entryId': 'task-vlm-step-1',
        'runLogId': 'task-vlm',
        'isFinal': false,
      },
    ),
    if (includeThinking)
      ChatMessageModel.cardMessage(
        const <String, dynamic>{
          'type': 'deep_thinking',
          'thinkingContent': _kThinkingFixtureText,
          'stage': 4,
          'isLoading': false,
          'taskID': 'task-vlm',
          'cardId': 'task-vlm-thinking',
        },
        id: 'task-vlm-thinking',
        streamMeta: const <String, dynamic>{
          'parentTaskId': 'task-vlm',
          'kind': 'thinking_snapshot',
          'seq': 20,
          'entryId': 'task-vlm-thinking',
          'isFinal': false,
        },
      ),
    ChatMessageModel.userMessage('打开设置', id: 'task-vlm-user'),
  ];
  return messages;
}

List<ChatMessageModel> _buildSingleThinkingAgentRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-thinking-only-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{
        'text': '最终回答',
        'id': 'task-thinking-only-text',
      },
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-thinking-only',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-thinking-only-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': _kThinkingFixtureText,
        'stage': 1,
        'isLoading': true,
        'taskID': 'task-thinking-only',
        'cardId': 'task-thinking-only-card',
      },
      id: 'task-thinking-only-card',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-thinking-only',
        'kind': 'thinking_snapshot',
        'seq': 20,
        'entryId': 'task-thinking-only-card',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-thinking-only-user'),
  ];
}

List<ChatMessageModel> _buildSingleToolAgentRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-tool-only-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{
        'text': '最终回答',
        'id': 'task-tool-only-text',
      },
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-tool-only',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-tool-only-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      const <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'terminal',
        'toolName': 'terminal_exec',
        'toolTitle': '运行 git status',
        'summary': '命令执行完成',
        'terminalOutput': 'On branch main',
        'argsJson': '{"command":"git status"}',
      },
      id: 'task-tool-only-card',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-tool-only',
        'kind': 'tool_completed',
        'seq': 20,
        'entryId': 'task-tool-only-card',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-tool-only-user'),
  ];
}

List<ChatMessageModel> _buildActiveAgentRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '最终回答', 'id': 'task-1-text'},
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-1-text',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'running',
        'toolType': 'terminal',
        'toolTitle': '运行 git status',
        'summary': '命令执行中',
        'terminalOutput': 'On branch main',
      },
      id: 'task-1-tool',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_progress',
        'seq': 20,
        'entryId': 'task-1-tool',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': _kThinkingFixtureText,
        'stage': 1,
        'isLoading': true,
        'taskID': 'task-1',
        'cardId': 'task-1-thinking',
      },
      id: 'task-1-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}
