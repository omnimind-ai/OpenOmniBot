import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/storage_service.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  const cacheChannel = MethodChannel('cn.com.omnimind.bot/CacheDataEvent');
  late List<Map<String, Object?>> nativeConversations;

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    nativeConversations = <Map<String, Object?>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return nativeConversations;
            case 'getWorkspaceLongMemory':
              return <String, Object?>{'content': ''};
            case 'agentSkillList':
              return <Object?>[];
            case 'updateConversationTitle':
              return 'SUCCESS';
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheChannel, (call) async {
          switch (call.method) {
            case 'getAllFavoriteRecords':
              return <Object?>[];
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheChannel, null);
  });

  testWidgets('embedded mode routes new conversation through callback', (
    tester,
  ) async {
    ConversationMode? selectedMode;

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: Scaffold(
              body: SizedBox(
                width: 360,
                height: 720,
                child: HomeDrawer(
                  embedded: true,
                  closeOnNavigate: false,
                  onThreadTargetSelected: (target) {
                    selectedMode = target.mode;
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('暂无聊天记录'), findsOneWidget);

    await tester.tap(find.text('开始对话'));
    await tester.pumpAndSettle();

    expect(selectedMode, ConversationMode.normal);
  });

  testWidgets(
    'embedded mode creates new chat_only conversation when requested',
    (tester) async {
      ConversationMode? selectedMode;

      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: _buildProviderScope(
              child: Scaffold(
                body: SizedBox(
                  width: 360,
                  height: 720,
                  child: HomeDrawer(
                    embedded: true,
                    closeOnNavigate: false,
                    newConversationMode: ConversationMode.chatOnly,
                    onThreadTargetSelected: (target) {
                      selectedMode = target.mode;
                    },
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('开始对话'));
      await tester.pumpAndSettle();

      expect(selectedMode, ConversationMode.chatOnly);
    },
  );

  testWidgets('embedded mode routes existing conversation through callback', (
    tester,
  ) async {
    ConversationThreadTarget? selectedTarget;
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 42,
        'title': '已存在会话',
        'mode': ConversationMode.openclaw.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': 'hello',
        'messageCount': 1,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: Scaffold(
              body: SizedBox(
                width: 360,
                height: 720,
                child: HomeDrawer(
                  embedded: true,
                  closeOnNavigate: false,
                  onThreadTargetSelected: (target) {
                    selectedTarget = target;
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('已存在会话'));
    await tester.pumpAndSettle();

    expect(selectedTarget, isNotNull);
    expect(selectedTarget!.conversationId, 42);
    expect(selectedTarget!.mode, ConversationMode.openclaw);
  });

  testWidgets('shows scheduled and pinned sections before regular history', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'packageName': '',
        'nodeId': '',
        'suggestionId': '',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '子运行会话',
        'mode': ConversationMode.subagent.storageValue,
        'parentConversationId': 1,
        'parentConversationMode': ConversationMode.normal.storageValue,
        'scheduledTaskId': 'schedule-1',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 2000,
        'updatedAt': now - 1000,
      },
      <String, Object?>{
        'id': 3,
        'title': '重点对话',
        'mode': ConversationMode.normal.storageValue,
        'isPinned': true,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
      <String, Object?>{
        'id': 4,
        'title': '普通会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 8000,
        'updatedAt': now - 7000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsOneWidget);
    expect(find.text('主会话'), findsOneWidget);
    expect(find.text('子运行会话'), findsOneWidget);
    expect(find.text('置顶会话'), findsOneWidget);
    expect(find.text('重点对话'), findsOneWidget);
    expect(find.text('普通会话'), findsOneWidget);

    final scheduledChildSlidable = tester.widget<ConversationSlidable>(
      find.ancestor(
        of: find.text('子运行会话'),
        matching: find.byType(ConversationSlidable),
      ),
    );
    expect(scheduledChildSlidable.actions, hasLength(2));
  });

  testWidgets('syncs scheduled section when scheduled tasks are deleted', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'packageName': '',
        'nodeId': '',
        'suggestionId': '',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '子运行会话',
        'mode': ConversationMode.subagent.storageValue,
        'parentConversationId': 1,
        'parentConversationMode': ConversationMode.normal.storageValue,
        'scheduledTaskId': 'schedule-1',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 2000,
        'updatedAt': now - 1000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsOneWidget);
    expect(find.text('主会话'), findsOneWidget);
    expect(find.text('子运行会话'), findsOneWidget);

    await ScheduledTaskStorageService.deleteScheduledTask('schedule-1');
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsNothing);
    expect(find.text('主会话'), findsOneWidget);
    expect(find.text('子运行会话'), findsOneWidget);
  });

  testWidgets('renames scheduled parent conversation from long press', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    String? renamedTitle;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return nativeConversations;
            case 'getWorkspaceLongMemory':
              return <String, Object?>{'content': ''};
            case 'agentSkillList':
              return <Object?>[];
            case 'updateConversationTitle':
              renamedTitle = (call.arguments as Map?)?['newTitle'] as String?;
              return 'SUCCESS';
            default:
              return null;
          }
        });
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'packageName': '',
        'nodeId': '',
        'suggestionId': '',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(find.text('主会话'));
    await tester.pumpAndSettle();
    final titleField = find.byWidgetPredicate(
      (widget) => widget is TextField && widget.controller?.text == '主会话',
    );
    expect(titleField, findsOneWidget);

    await tester.enterText(titleField, '主会话改名');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();

    expect(renamedTitle, '主会话改名');
    expect(find.text('主会话改名'), findsOneWidget);
  });

  testWidgets('persists drawer section expanded states across openings', (
    tester,
  ) async {
    final currentDay = DateTime.now();
    final now = DateTime(
      currentDay.year,
      currentDay.month,
      currentDay.day,
      12,
    ).millisecondsSinceEpoch;
    final dateKey =
        '__home_drawer_date__'
        '${currentDay.year.toString().padLeft(4, '0')}-'
        '${currentDay.month.toString().padLeft(2, '0')}-'
        '${currentDay.day.toString().padLeft(2, '0')}';
    final todayLabel = ConversationModel(
      id: 999,
      title: '',
      status: 0,
      messageCount: 0,
      createdAt: now,
      updatedAt: now,
    ).timeDisplay;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'packageName': '',
        'nodeId': '',
        'suggestionId': '',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '子运行会话',
        'mode': ConversationMode.subagent.storageValue,
        'parentConversationId': 1,
        'parentConversationMode': ConversationMode.normal.storageValue,
        'scheduledTaskId': 'schedule-1',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 2000,
        'updatedAt': now - 1000,
      },
      <String, Object?>{
        'id': 3,
        'title': '重点对话',
        'mode': ConversationMode.normal.storageValue,
        'isPinned': true,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
      <String, Object?>{
        'id': 4,
        'title': '普通会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 8000,
        'updatedAt': now - 7000,
      },
    ];

    Widget drawerWidget() {
      return MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      );
    }

    await tester.pumpWidget(drawerWidget());
    await tester.pumpAndSettle();

    expect(find.text('主会话').hitTestable(), findsOneWidget);
    expect(find.text('子运行会话').hitTestable(), findsOneWidget);
    expect(find.text('重点对话').hitTestable(), findsOneWidget);
    expect(find.text('普通会话').hitTestable(), findsOneWidget);

    final parentTitleRect = tester.getRect(find.text('主会话'));
    await tester.tapAt(
      Offset(parentTitleRect.left - 14, parentTitleRect.center.dy),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('定时任务'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('置顶会话'));
    await tester.pumpAndSettle();
    await tester.tap(find.text(todayLabel));
    await tester.pumpAndSettle();

    final rawState = StorageService.getString(
      'home_drawer_expanded_sections_v1',
    );
    expect(rawState, contains('__home_drawer_scheduled__'));
    expect(rawState, contains('__home_drawer_pinned__'));
    expect(rawState, contains('__home_drawer_scheduled_normal:1'));
    expect(rawState, contains(dateKey));

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    await tester.pumpWidget(drawerWidget());
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsOneWidget);
    expect(find.text('置顶会话'), findsOneWidget);
    expect(find.text(todayLabel), findsOneWidget);
    expect(find.text('主会话').hitTestable(), findsNothing);
    expect(find.text('子运行会话').hitTestable(), findsNothing);
    expect(find.text('重点对话').hitTestable(), findsNothing);
    expect(find.text('普通会话').hitTestable(), findsNothing);
  });
}

Widget _buildProviderScope({required Widget child}) {
  return ProviderScope(
    overrides: [
      habitualHandProvider.overrideWith(
        (ref) => HabitualHandController(initial: HabitualHand.right),
      ),
    ],
    child: child,
  );
}
