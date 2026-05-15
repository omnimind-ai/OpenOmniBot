import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/habitual_hand.dart';

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

  setUp(() {
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

  testWidgets('renders running and completed indicators in conversation list', (
    tester,
  ) async {
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 51,
        'title': '正在执行的会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': 'running',
        'messageCount': 1,
        'createdAt': 1,
        'updatedAt': 3,
      },
      <String, Object?>{
        'id': 52,
        'title': '已完成的会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 1,
        'lastMessage': 'done',
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
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('conversation-status-running')), findsOne);
    expect(
      find.byKey(const ValueKey('conversation-status-completed')),
      findsOne,
    );
  });

  testWidgets('project shortcut opens project management page', (tester) async {
    final router = GoRouter(
      navigatorKey: GoRouterManager.rootNavigatorKey,
      initialLocation: '/drawer',
      routes: [
        GoRoute(
          path: '/drawer',
          builder: (context, state) => DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: _buildProviderScope(
              child: const Scaffold(
                body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
              ),
            ),
          ),
        ),
        GoRoute(
          path: '/home/chat',
          builder: (context, state) => const Scaffold(body: Text('chat route')),
        ),
        GoRoute(
          path: '/workbench/projects',
          builder: (context, state) =>
              const Scaffold(body: Text('project management route')),
        ),
      ],
    );

    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('项目'));
    await tester.pumpAndSettle();

    expect(find.text('project management route'), findsOneWidget);
    expect(find.text('chat route'), findsNothing);
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
