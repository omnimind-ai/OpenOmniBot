import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const speechChannel = MethodChannel('cn.com.omnimind.bot/SpeechRecognition');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    messenger.setMockMethodCallHandler(speechChannel, (call) async {
      if (call.method == 'initialize') {
        return true;
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(speechChannel, null);
  });

  testWidgets('does not render context usage ring when ratio is absent', (
    tester,
  ) async {
    await tester.pumpWidget(_buildTestApp(contextUsageRatio: null));
    await tester.pump();

    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_ContextUsageRingPainter',
      ),
      findsNothing,
    );
  });

  testWidgets('renders context usage ring when ratio is provided', (
    tester,
  ) async {
    await tester.pumpWidget(_buildTestApp(contextUsageRatio: 0.72));
    await tester.pump();

    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_ContextUsageRingPainter',
      ),
      findsOneWidget,
    );
  });

  testWidgets('long pressing context usage ring triggers callback', (
    tester,
  ) async {
    var longPressed = false;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: 0.72,
        onLongPressContextUsageRing: () {
          longPressed = true;
        },
      ),
    );
    await tester.pump();

    await tester.longPress(
      find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_ContextUsageRingPainter',
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));

    expect(longPressed, isTrue);
  });

  testWidgets('tapping slash trigger button invokes callback', (tester) async {
    var tapped = false;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        onTriggerSlashCommand: () {
          tapped = true;
        },
      ),
    );
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('chat-input-trigger-slash-button')),
    );
    await tester.pump(const Duration(milliseconds: 300));

    expect(tapped, isTrue);
  });

  testWidgets('codex permission selector opens menu and selects mode', (
    tester,
  ) async {
    CodexPermissionMode? selected;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        codexPermissionMode: CodexPermissionMode.fullAccess,
        onCodexPermissionModeChanged: (mode) {
          selected = mode;
        },
      ),
    );
    await tester.pump();

    final permissionButton = find.byKey(
      const ValueKey('chat-input-codex-permission-button'),
    );
    expect(
      find.descendant(of: permissionButton, matching: find.byType(SvgPicture)),
      findsOneWidget,
    );

    await tester.tap(permissionButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-defaultMode'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-autoReview'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-fullAccess'),
      ),
      findsOneWidget,
    );
    for (final mode in CodexPermissionMode.values) {
      expect(
        find.descendant(
          of: find.byKey(
            ValueKey('chat-input-codex-permission-option-${mode.name}'),
          ),
          matching: find.byType(SvgPicture),
        ),
        findsOneWidget,
      );
    }

    await tester.tap(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-autoReview'),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(selected, CodexPermissionMode.autoReview);
  });

  testWidgets('large composer starts collapsed for empty unfocused input', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.keyboardType, TextInputType.multiline);
    expect(field.textInputAction, TextInputAction.newline);
    expect(field.minLines, 1);
    expect(field.maxLines, 3);
  });

  testWidgets('large composer expands when soft keyboard is visible', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.minLines, 2);
    expect(field.maxLines, 3);
    tester.view.resetViewInsets();
  });

  testWidgets('large composer collapses when keyboard hides while focused', (
    tester,
  ) async {
    final focusNode = FocusNode();
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        focusNode: focusNode,
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 2);

    tester.view.resetViewInsets();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(focusNode.hasFocus, isTrue);
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 1);
  });

  testWidgets('large composer starts collapsing while keyboard is closing', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 2);

    tester.view.viewInsets = const FakeViewPadding(bottom: 280);
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 1);
    tester.view.resetViewInsets();
  });

  testWidgets('large composer resizes from bottom to keep actions anchored', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    final animatedSize = tester.widget<AnimatedSize>(find.byType(AnimatedSize));
    expect(animatedSize.alignment, Alignment.bottomCenter);
  });

  testWidgets('large composer stays expanded for existing text without focus', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        initialText: 'draft',
      ),
    );
    await tester.pump();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.minLines, 2);
    expect(field.maxLines, 3);
  });

  testWidgets('compact composer keeps send action', (tester) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: false),
    );
    await tester.pump();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.keyboardType, TextInputType.text);
    expect(field.textInputAction, TextInputAction.send);
    expect(field.maxLines, 1);
  });

  testWidgets('trajectory shortcut opens popup and dispatches latest action', (
    tester,
  ) async {
    final inputKey = GlobalKey<ChatInputAreaState>();
    final controller = TextEditingController();
    final focusNode = FocusNode();
    var popupVisible = false;
    var latestTapCount = 0;
    var listTapCount = 0;
    var recordTapCount = 0;

    await tester.pumpWidget(
      DefaultAssetBundle(
        bundle: _TestAssetBundle(),
        child: MaterialApp(
          home: StatefulBuilder(
            builder: (context, setState) {
              return Scaffold(
                body: Stack(
                  children: [
                    Align(
                      alignment: Alignment.bottomCenter,
                      child: ChatInputArea(
                        key: inputKey,
                        controller: controller,
                        focusNode: focusNode,
                        isProcessing: false,
                        onSendMessage: () {},
                        onCancelTask: () {},
                        onPopupVisibilityChanged: (visible) {
                          setState(() => popupVisible = visible);
                        },
                        onViewTrajectoriesTap: () {
                          listTapCount += 1;
                        },
                        onViewCurrentTrajectoryTap: () {
                          latestTapCount += 1;
                        },
                        onManualRecordingTap: () {
                          recordTapCount += 1;
                        },
                      ),
                    ),
                    if (popupVisible)
                      Positioned(
                        left: 12,
                        bottom: 84,
                        child:
                            inputKey.currentState?.buildPopupMenu() ??
                            const SizedBox.shrink(),
                      ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
    addTearDown(controller.dispose);
    addTearDown(focusNode.dispose);
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('chat-input-manual-recording-button')),
    );
    await tester.pump();

    expect(
      find.byKey(const ValueKey('chat-input-trajectory-popup')),
      findsOneWidget,
    );
    expect(find.text('已有轨迹'), findsOneWidget);
    expect(find.text('当前轨迹'), findsOneWidget);
    expect(find.text('录制轨迹'), findsOneWidget);

    await tester.tap(find.text('当前轨迹'));
    await tester.pump();

    expect(latestTapCount, 1);
    expect(listTapCount, 0);
    expect(recordTapCount, 0);
    expect(
      find.byKey(const ValueKey('chat-input-trajectory-popup')),
      findsNothing,
    );
  });
}

Widget _buildTestApp({
  required double? contextUsageRatio,
  VoidCallback? onLongPressContextUsageRing,
  VoidCallback? onTriggerSlashCommand,
  bool useLargeComposerStyle = false,
  CodexPermissionMode? codexPermissionMode,
  ValueChanged<CodexPermissionMode>? onCodexPermissionModeChanged,
  String initialText = '',
  FocusNode? focusNode,
}) {
  return DefaultAssetBundle(
    bundle: _TestAssetBundle(),
    child: MaterialApp(
      home: Scaffold(
        body: ChatInputArea(
          controller: TextEditingController(text: initialText),
          focusNode: focusNode ?? FocusNode(),
          isProcessing: false,
          onSendMessage: () {},
          onCancelTask: () {},
          useLargeComposerStyle: useLargeComposerStyle,
          contextUsageRatio: contextUsageRatio,
          onLongPressContextUsageRing: onLongPressContextUsageRing,
          onTriggerSlashCommand: onTriggerSlashCommand,
          codexPermissionMode: codexPermissionMode,
          onCodexPermissionModeChanged: onCodexPermissionModeChanged,
        ),
      ),
    ),
  );
}

class _TestAssetBundle extends CachingAssetBundle {
  static const String _svg = '''
<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
  <circle cx="12" cy="12" r="10" stroke="#1930D9" stroke-width="2"/>
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
