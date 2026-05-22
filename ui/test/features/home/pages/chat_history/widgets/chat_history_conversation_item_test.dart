import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/pages/chat_history/widgets/chat_history_conversation_item.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/habitual_hand.dart';

void main() {
  testWidgets('tap still opens the conversation item', (tester) async {
    var tapCount = 0;

    await tester.pumpWidget(
      _buildTestApp(
        child: ChatHistoryConversationItem(
          conversation: _conversation(id: 1, title: 'Conversation A'),
          actions: _deleteActions(() {}),
          onTap: () => tapCount++,
          onDelete: () {},
        ),
      ),
    );

    await tester.tap(find.text('Conversation A'));
    await tester.pumpAndSettle();

    expect(tapCount, 1);
  });

  testWidgets('tap delete action triggers delete callback once', (
    tester,
  ) async {
    var deleteCount = 0;

    await tester.pumpWidget(
      _buildTestApp(
        child: ChatHistoryConversationItem(
          conversation: _conversation(id: 2, title: 'Conversation B'),
          actions: _deleteActions(() => deleteCount++),
          onTap: () {},
          onDelete: () => deleteCount++,
        ),
      ),
    );

    await tester.drag(find.byType(Slidable), const Offset(-220, 0));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(CustomSlidableAction));
    await tester.pumpAndSettle();

    expect(deleteCount, 1);
  });

  testWidgets('dragging far still exposes a quick delete action', (
    tester,
  ) async {
    var deleteCount = 0;

    await tester.pumpWidget(
      _buildTestApp(
        child: ChatHistoryConversationItem(
          conversation: _conversation(id: 3, title: 'Conversation C'),
          actions: _deleteActions(() => deleteCount++),
          onTap: () {},
          onDelete: () => deleteCount++,
        ),
      ),
    );

    await tester.drag(find.byType(Slidable), const Offset(-260, 0));
    await tester.pumpAndSettle();

    expect(find.byType(CustomSlidableAction), findsOneWidget);

    await tester.tap(find.byType(CustomSlidableAction));
    await tester.pumpAndSettle();

    expect(deleteCount, 1);
  });

  testWidgets('left hand preference reveals actions with a right swipe', (
    tester,
  ) async {
    var deleteCount = 0;

    await tester.pumpWidget(
      _buildTestApp(
        habitualHand: HabitualHand.left,
        child: ChatHistoryConversationItem(
          conversation: _conversation(id: 5, title: 'Conversation Left'),
          actions: _deleteActions(() => deleteCount++),
          onTap: () {},
          onDelete: () => deleteCount++,
        ),
      ),
    );

    await tester.drag(find.byType(Slidable), const Offset(220, 0));
    await tester.pumpAndSettle();

    expect(find.byType(CustomSlidableAction), findsOneWidget);

    await tester.tap(find.byType(CustomSlidableAction));
    await tester.pumpAndSettle();

    expect(deleteCount, 1);
  });

  testWidgets('left swipe does not reveal actions for left hand preference', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(
        habitualHand: HabitualHand.left,
        child: ChatHistoryConversationItem(
          conversation: _conversation(id: 6, title: 'Conversation Left Guard'),
          actions: _deleteActions(() {}),
          onTap: () {},
          onDelete: () {},
        ),
      ),
    );

    await tester.drag(find.byType(Slidable), const Offset(-220, 0));
    await tester.pumpAndSettle();

    expect(find.byType(CustomSlidableAction), findsNothing);
  });

  testWidgets('full swipe uses the override callback when provided', (
    tester,
  ) async {
    var deleteCount = 0;
    var archiveCount = 0;

    await tester.pumpWidget(
      _buildTestApp(
        child: ConversationSlidable(
          itemKey: 'conversation-override',
          groupTag: 'test-group',
          actions: _deleteActions(() => deleteCount++),
          onDismissed: () => deleteCount++,
          onFullSwipe: () => archiveCount++,
          child: const SizedBox(height: 64, child: Text('Conversation E')),
        ),
      ),
    );

    await tester.timedDrag(
      find.byType(Slidable),
      const Offset(-640, 0),
      const Duration(milliseconds: 400),
    );
    await tester.pumpAndSettle();

    expect(archiveCount, 1);
    expect(deleteCount, 0);
  });

  testWidgets('full swipe still falls back to onDismissed by default', (
    tester,
  ) async {
    var deleteCount = 0;

    await tester.pumpWidget(
      _buildTestApp(
        child: ConversationSlidable(
          itemKey: 'conversation-default',
          groupTag: 'test-group',
          actions: _deleteActions(() => deleteCount++),
          onDismissed: () => deleteCount++,
          child: const SizedBox(height: 64, child: Text('Conversation F')),
        ),
      ),
    );

    await tester.timedDrag(
      find.byType(Slidable),
      const Offset(-640, 0),
      const Duration(milliseconds: 400),
    );
    await tester.pumpAndSettle();

    expect(deleteCount, 1);
  });

  testWidgets('renders a mode badge for OpenClaw threads', (tester) async {
    await tester.pumpWidget(
      _buildTestApp(
        child: ChatHistoryConversationItem(
          conversation: _conversation(
            id: 4,
            title: 'Conversation D',
            mode: ConversationMode.openclaw,
          ),
          actions: _deleteActions(() {}),
          onTap: () {},
          onDelete: () {},
        ),
      ),
    );

    expect(find.text('OpenClaw'), findsOneWidget);
  });
}

Widget _buildTestApp({
  required Widget child,
  HabitualHand habitualHand = HabitualHand.right,
}) {
  return DefaultAssetBundle(
    bundle: _TestAssetBundle(),
    child: ProviderScope(
      overrides: [
        habitualHandProvider.overrideWith(
          (ref) => HabitualHandController(initial: habitualHand),
        ),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SlidableAutoCloseBehavior(child: ListView(children: [child])),
        ),
      ),
    ),
  );
}

List<ConversationSlideAction> _deleteActions(VoidCallback onDelete) {
  return [
    ConversationSlideAction(
      onPressed: onDelete,
      backgroundColor: const Color(0xFFE53935),
      child: const Icon(Icons.delete_outline, color: Colors.white),
    ),
  ];
}

ConversationModel _conversation({
  required int id,
  required String title,
  ConversationMode mode = ConversationMode.normal,
}) {
  return ConversationModel(
    id: id,
    mode: mode,
    title: title,
    summary: 'Summary',
    status: 0,
    lastMessage: 'Last message',
    messageCount: 3,
    createdAt: DateTime(2026, 3, 20, 9).millisecondsSinceEpoch,
    updatedAt: DateTime(2026, 3, 20, 10).millisecondsSinceEpoch,
  );
}

class _TestAssetBundle extends CachingAssetBundle {
  static const String _svg = '''
<svg width="20" height="20" viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="20" height="20" fill="#FFFFFF"/>
</svg>
''';

  @override
  Future<ByteData> load(String key) async {
    final bytes = Uint8List.fromList(utf8.encode(_svg));
    return ByteData.view(bytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return _svg;
  }
}
