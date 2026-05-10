import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const codexChannel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<Map<String, dynamic>> nativeConversations;
  late List<MethodCall> codexCalls;
  late bool codexArchiveShouldThrow;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    nativeConversations = <Map<String, dynamic>>[];
    codexCalls = <MethodCall>[];
    codexArchiveShouldThrow = false;
    messenger.setMockMethodCallHandler(channel, (call) async {
      final args = Map<String, dynamic>.from(
        (call.arguments as Map?) ?? const {},
      );
      switch (call.method) {
        case 'getConversations':
          return nativeConversations;
        case 'createConversation':
          final nextId =
              nativeConversations.fold<int>(
                0,
                (maxId, item) =>
                    item['id'] as int > maxId ? item['id'] as int : maxId,
              ) +
              1;
          nativeConversations.add({
            'id': nextId,
            'title': args['title'] ?? '新对话',
            'mode': args['mode'] ?? ConversationMode.normal.storageValue,
            'summary': args['summary'],
            'status': 0,
            'lastMessage': null,
            'messageCount': 0,
            'createdAt': 1,
            'updatedAt': 1,
          });
          return nextId;
        case 'updateConversation':
          final conversation = Map<String, dynamic>.from(
            (args['conversation'] as Map).cast<String, dynamic>(),
          );
          final conversationId = (conversation['id'] as num?)?.toInt();
          final index = nativeConversations.indexWhere(
            (item) => item['id'] == conversationId,
          );
          if (index >= 0) {
            nativeConversations[index] = <String, dynamic>{
              ...nativeConversations[index],
              ...conversation,
            };
          }
          return 'SUCCESS';
        case 'updateConversationTitle':
        case 'completeConversation':
        case 'setCurrentConversationId':
          return 'SUCCESS';
        case 'updateConversationPromptTokenThreshold':
          final conversationId = (args['conversationId'] as num?)?.toInt();
          final threshold = (args['promptTokenThreshold'] as num?)?.toInt();
          final index = nativeConversations.indexWhere(
            (item) => item['id'] == conversationId,
          );
          if (index >= 0 && threshold != null) {
            nativeConversations[index] = <String, dynamic>{
              ...nativeConversations[index],
              'promptTokenThreshold': threshold,
            };
          }
          return 'SUCCESS';
        case 'deleteConversation':
          final conversationId = (args['conversationId'] as num?)?.toInt();
          nativeConversations.removeWhere(
            (item) => item['id'] == conversationId,
          );
          return 'SUCCESS';
        default:
          return null;
      }
    });
    messenger.setMockMethodCallHandler(codexChannel, (call) async {
      codexCalls.add(call);
      if (codexArchiveShouldThrow &&
          (call.method == 'thread/archive' ||
              call.method == 'thread/unarchive')) {
        throw PlatformException(
          code: 'CODEX_THREAD_NOT_FOUND',
          message: 'thread not found',
        );
      }
      return <String, dynamic>{'ok': true};
    });
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(codexChannel, null);
  });

  test('loads conversations from native source', () async {
    nativeConversations = <Map<String, dynamic>>[
      {
        'id': 42,
        'title': 'openclaw hello',
        'mode': ConversationMode.openclaw.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': 'openclaw hello',
        'messageCount': 2,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    final conversations = await ConversationService.getAllConversations();

    expect(conversations, hasLength(1));
    expect(conversations.single.id, 42);
    expect(conversations.single.mode, ConversationMode.openclaw);
    expect(conversations.single.title, 'openclaw hello');
  });

  test('loads chat_only conversations without collapsing mode', () async {
    nativeConversations = <Map<String, dynamic>>[
      {
        'id': 8,
        'title': '纯聊线程',
        'mode': ConversationMode.chatOnly.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': '你好',
        'messageCount': 1,
        'createdAt': 1,
        'updatedAt': 3,
      },
    ];

    final conversations = await ConversationService.getAllConversations();

    expect(conversations, hasLength(1));
    expect(conversations.single.mode, ConversationMode.chatOnly);
    expect(
      await ConversationService.getLatestConversation(
        mode: ConversationMode.chatOnly,
      ),
      isNotNull,
    );
  });

  test('parses context compaction metadata from native source', () async {
    nativeConversations = <Map<String, dynamic>>[
      {
        'id': 7,
        'title': 'normal hello',
        'mode': ConversationMode.normal.storageValue,
        'summary': '摘要',
        'contextSummary': '【用户目标与约束】\n- 测试',
        'contextSummaryCutoffEntryDbId': 33,
        'contextSummaryUpdatedAt': 101,
        'status': 0,
        'lastMessage': 'hello',
        'messageCount': 9,
        'latestPromptTokens': 64000,
        'promptTokenThreshold': 128000,
        'latestPromptTokensUpdatedAt': 202,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    final conversations = await ConversationService.getAllConversations();

    expect(conversations, hasLength(1));
    expect(conversations.single.contextSummary, contains('用户目标'));
    expect(conversations.single.contextSummaryCutoffEntryDbId, 33);
    expect(conversations.single.latestPromptTokens, 64000);
    expect(conversations.single.promptTokenThreshold, 128000);
    expect(conversations.single.contextUsageRatio, closeTo(0.5, 0.0001));
  });

  test(
    'updates conversation prompt token threshold via native channel',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 11,
          'title': 'normal hello',
          'mode': ConversationMode.normal.storageValue,
          'summary': null,
          'status': 0,
          'lastMessage': null,
          'messageCount': 0,
          'promptTokenThreshold': 128000,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];

      final updated =
          await ConversationService.updateConversationPromptTokenThreshold(
            conversationId: 11,
            promptTokenThreshold: 400000,
          );

      expect(updated, isTrue);
      expect(nativeConversations.single['promptTokenThreshold'], 400000);
    },
  );

  test(
    'deletes only the targeted thread metadata and keeps other modes intact',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 1,
          'title': 'normal thread',
          'mode': ConversationMode.normal.storageValue,
          'summary': null,
          'status': 0,
          'lastMessage': null,
          'messageCount': 0,
          'createdAt': 1,
          'updatedAt': 1,
        },
        {
          'id': 2,
          'title': 'openclaw thread',
          'mode': ConversationMode.openclaw.storageValue,
          'summary': null,
          'status': 0,
          'lastMessage': null,
          'messageCount': 0,
          'createdAt': 2,
          'updatedAt': 2,
        },
      ];
      await ConversationHistoryService.saveCurrentConversationId(
        1,
        mode: ConversationMode.normal,
      );
      await ConversationHistoryService.saveCurrentConversationId(
        2,
        mode: ConversationMode.openclaw,
      );
      await ConversationHistoryService.saveLastVisibleThreadTarget(
        const ConversationThreadTarget.existing(
          conversationId: 2,
          mode: ConversationMode.openclaw,
        ),
      );

      final deleted = await ConversationService.deleteConversation(
        2,
        mode: ConversationMode.openclaw,
      );

      expect(deleted, isTrue);
      expect(
        await ConversationHistoryService.getCurrentConversationId(
          mode: ConversationMode.normal,
        ),
        1,
      );
      expect(
        await ConversationHistoryService.getCurrentConversationId(
          mode: ConversationMode.openclaw,
        ),
        isNull,
      );
      expect(
        await ConversationHistoryService.getLastVisibleThreadTarget(),
        const ConversationThreadTarget.existing(
          conversationId: 1,
          mode: ConversationMode.normal,
        ),
      );

      final remaining = await ConversationService.getAllConversations();
      expect(remaining, hasLength(1));
      expect(remaining.single.id, 1);
      expect(remaining.single.mode, ConversationMode.normal);
    },
  );

  test('creates conversations with chat_only mode', () async {
    final conversationId = await ConversationService.createConversation(
      title: '纯聊新线程',
      mode: ConversationMode.chatOnly,
    );

    expect(conversationId, isNotNull);
    final created = nativeConversations.singleWhere(
      (item) => item['id'] == conversationId,
    );
    expect(created['mode'], ConversationMode.chatOnly.storageValue);
  });

  test(
    'archives codex conversation locally when app-server archive fails',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 9,
          'title': 'Codex thread',
          'mode': ConversationMode.codex.storageValue,
          'summary': null,
          'isArchived': false,
          'status': 0,
          'lastMessage': 'hello',
          'messageCount': 2,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];
      codexArchiveShouldThrow = true;

      final archived = await ConversationService.archiveConversation(
        ConversationModel.fromJson(nativeConversations.single),
      );

      expect(archived, isTrue);
      expect(codexCalls.single.method, 'thread/archive');
      expect(nativeConversations.single['isArchived'], isTrue);
    },
  );

  test(
    'delete codex conversation soft-archives locally when binding is stale',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 10,
          'title': 'Codex stale binding',
          'mode': ConversationMode.codex.storageValue,
          'summary': null,
          'isArchived': false,
          'status': 0,
          'lastMessage': 'hello',
          'messageCount': 2,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];
      codexArchiveShouldThrow = true;

      final deleted = await ConversationService.deleteConversation(
        10,
        mode: ConversationMode.codex,
      );

      expect(deleted, isTrue);
      expect(codexCalls.single.method, 'thread/archive');
      expect(nativeConversations.single['isArchived'], isTrue);
    },
  );
}
