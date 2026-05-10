import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/streaming_text.dart';
import 'package:ui/widgets/typewriter_text.dart';

void main() {
  testWidgets('StreamingText keeps surrogate pairs intact during animation', (
    tester,
  ) async {
    const text = '前缀📎后缀';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(fullText: text, style: TextStyle(fontSize: 14)),
        ),
      ),
    );

    await tester.pump(const Duration(milliseconds: 20));
    expect(tester.takeException(), isNull);

    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    final richText = tester.widget<RichText>(
      find.byWidgetPredicate(
        (widget) =>
            widget is RichText && widget.text.toPlainText().contains('前缀'),
      ),
    );
    expect(richText.text.toPlainText(), text);
  });

  testWidgets('TypewriterText advances past emoji without splitting it', (
    tester,
  ) async {
    const text = '前缀📎后缀';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: TypewriterText(
            text: text,
            style: TextStyle(fontSize: 14),
            shouldAnimate: true,
          ),
        ),
      ),
    );

    for (var index = 0; index < text.length + 2; index += 1) {
      await tester.pump(const Duration(milliseconds: 15));
      expect(tester.takeException(), isNull);
    }

    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    final markdownBody = tester.widget<OmnibotMarkdownBody>(
      find.byType(OmnibotMarkdownBody),
    );
    expect(markdownBody.data, text);
  });

  testWidgets(
    'StreamingText resets animation state when text is replaced by a new snapshot',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              fullText: '第一版内容',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              fullText: '改写后的全新内容 😀',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      final richText = tester.widget<RichText>(
        find.byWidgetPredicate(
          (widget) =>
              widget is RichText &&
              widget.text.toPlainText().contains('改写后的全新内容'),
        ),
      );
      expect(richText.text.toPlainText(), '改写后的全新内容 😀');
    },
  );

  testWidgets(
    'StreamingText renders markdown snapshots after replacement without exceptions',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              fullText: '旧内容',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              fullText: '**新内容** 😀',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      final markdownBody = tester.widget<OmnibotMarkdownBody>(
        find.byType(OmnibotMarkdownBody),
      );
      expect(markdownBody.data, '**新内容** 😀');
    },
  );
}
