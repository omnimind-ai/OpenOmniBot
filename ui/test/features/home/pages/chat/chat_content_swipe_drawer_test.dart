import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';

enum _SurfaceMode { normal, workspace }

class _ChatContentDrawerSwipeHarness extends StatefulWidget {
  const _ChatContentDrawerSwipeHarness();

  @override
  State<_ChatContentDrawerSwipeHarness> createState() =>
      _ChatContentDrawerSwipeHarnessState();
}

class _ChatContentDrawerSwipeHarnessState
    extends State<_ChatContentDrawerSwipeHarness> {
  static const double _drawerSwipeThreshold = 36;

  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  final GlobalKey _inputAreaKey = GlobalKey();
  final PageController _pageController = PageController(initialPage: 0);
  final ScrollController _attachmentScrollController = ScrollController(
    initialScrollOffset: 160,
  );

  _SurfaceMode _activeMode = _SurfaceMode.normal;
  int? _pointerId;
  double _horizontalDragDelta = 0;
  double _verticalDragDelta = 0;

  double get attachmentOffset => _attachmentScrollController.offset;

  bool _isPointerInside(GlobalKey key, Offset position) {
    final context = key.currentContext;
    if (context == null) {
      return false;
    }
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) {
      return false;
    }
    final rect = renderBox.localToGlobal(Offset.zero) & renderBox.size;
    return rect.contains(position);
  }

  void _handlePointerDown(PointerDownEvent event) {
    if (_activeMode != _SurfaceMode.normal) {
      _pointerId = null;
      _horizontalDragDelta = 0;
      _verticalDragDelta = 0;
      return;
    }
    if (_isPointerInside(_inputAreaKey, event.position)) {
      _pointerId = null;
      _horizontalDragDelta = 0;
      _verticalDragDelta = 0;
      return;
    }
    _pointerId = event.pointer;
    _horizontalDragDelta = 0;
    _verticalDragDelta = 0;
  }

  void _handlePointerMove(PointerMoveEvent event) {
    if (event.pointer != _pointerId || _activeMode != _SurfaceMode.normal) {
      return;
    }
    if (ChatDrawerGestureGate.containsPointer(event.pointer)) {
      _pointerId = null;
      _horizontalDragDelta = 0;
      _verticalDragDelta = 0;
      return;
    }
    _horizontalDragDelta += event.delta.dx;
    _verticalDragDelta += event.delta.dy;
  }

  void _handlePointerUp(PointerUpEvent event) {
    if (event.pointer != _pointerId) {
      return;
    }
    final shouldOpenDrawer =
        _activeMode == _SurfaceMode.normal &&
        _horizontalDragDelta >= _drawerSwipeThreshold &&
        _horizontalDragDelta.abs() > _verticalDragDelta.abs() * 1.2;
    if (shouldOpenDrawer) {
      _scaffoldKey.currentState?.openDrawer();
    }
    _pointerId = null;
    _horizontalDragDelta = 0;
    _verticalDragDelta = 0;
  }

  void _handlePointerCancel(PointerCancelEvent event) {
    if (event.pointer != _pointerId) {
      return;
    }
    _pointerId = null;
    _horizontalDragDelta = 0;
    _verticalDragDelta = 0;
  }

  @override
  void dispose() {
    _attachmentScrollController.dispose();
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        key: _scaffoldKey,
        drawerEnableOpenDragGesture: false,
        drawer: const Drawer(
          child: SafeArea(child: Center(child: Text('home_drawer'))),
        ),
        body: Column(
          children: [
            Text('active:${_activeMode.name}'),
            TextButton(
              key: const ValueKey('go-workspace'),
              onPressed: () {
                _pageController.jumpToPage(1);
              },
              child: const Text('go-workspace'),
            ),
            Expanded(
              child: Listener(
                behavior: HitTestBehavior.translucent,
                onPointerDown: _handlePointerDown,
                onPointerMove: _handlePointerMove,
                onPointerUp: _handlePointerUp,
                onPointerCancel: _handlePointerCancel,
                child: Stack(
                  children: [
                    PageView(
                      controller: _pageController,
                      onPageChanged: (page) {
                        setState(() {
                          _activeMode = page == 0
                              ? _SurfaceMode.normal
                              : _SurfaceMode.workspace;
                        });
                      },
                      children: const [
                        _ChatContentSurface(),
                        ColoredBox(
                          key: ValueKey('workspace-surface'),
                          color: Color(0xFFEFF4FF),
                          child: SizedBox.expand(),
                        ),
                      ],
                    ),
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: Container(
                        key: _inputAreaKey,
                        height: 96,
                        color: const Color(0xFFFFFFFF),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 12,
                        ),
                        child: ListView.separated(
                          key: const ValueKey('attachment-strip'),
                          controller: _attachmentScrollController,
                          scrollDirection: Axis.horizontal,
                          itemCount: 8,
                          separatorBuilder: (_, _) => const SizedBox(width: 8),
                          itemBuilder: (_, index) {
                            return Container(
                              width: 120,
                              alignment: Alignment.center,
                              decoration: BoxDecoration(
                                color: const Color(0xFFEAF2FF),
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Text('attachment-$index'),
                            );
                          },
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

void main() {
  testWidgets('right swipe on chat content opens home drawer', (tester) async {
    await tester.pumpWidget(const _ChatContentDrawerSwipeHarness());

    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('home_drawer'), findsNothing);

    await tester.drag(
      find.byKey(const ValueKey('chat-content-surface')),
      const Offset(160, 0),
    );
    await tester.pumpAndSettle();

    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('home_drawer'), findsOneWidget);
  });

  testWidgets('right swipe on guarded content list does not open home drawer', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatContentDrawerSwipeHarness());

    expect(find.text('active:normal'), findsOneWidget);
    final contentState = tester.state<_ChatContentSurfaceState>(
      find.byType(_ChatContentSurface),
    );
    final beforeOffset = contentState.horizontalOffset;

    await tester.drag(
      find.byKey(const ValueKey('content-horizontal-list')),
      const Offset(120, 0),
    );
    await tester.pumpAndSettle();

    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('home_drawer'), findsNothing);
    expect(contentState.horizontalOffset, lessThan(beforeOffset));
  });

  testWidgets('right swipe inside markdown table does not open home drawer', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatContentDrawerSwipeHarness());

    final tableHost = find.byKey(const ValueKey('markdown-table-host'));
    final tableScrollable = find.descendant(
      of: tableHost,
      matching: find.byType(Scrollable),
    );

    expect(tableScrollable, findsOneWidget);

    final scrollableState = tester.state<ScrollableState>(tableScrollable);
    expect(scrollableState.position.pixels, 0);

    await tester.drag(tableHost, const Offset(-240, 0));
    await tester.pumpAndSettle();

    final afterLeftDragOffset = scrollableState.position.pixels;
    expect(afterLeftDragOffset, greaterThan(0));

    await tester.drag(tableHost, const Offset(120, 0));
    await tester.pumpAndSettle();

    expect(find.text('home_drawer'), findsNothing);
    expect(scrollableState.position.pixels, lessThan(afterLeftDragOffset));
  });

  testWidgets('left swipe on chat content still enters workspace', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatContentDrawerSwipeHarness());

    expect(find.text('active:normal'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(-320, 0), 1200);
    await tester.pumpAndSettle();

    expect(find.text('active:workspace'), findsOneWidget);
  });

  testWidgets('workspace surface still supports content swipe back', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatContentDrawerSwipeHarness());

    await tester.tap(find.byKey(const ValueKey('go-workspace')));
    await tester.pumpAndSettle();

    expect(find.text('active:workspace'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(320, 0), 1200);
    await tester.pumpAndSettle();

    expect(find.text('active:normal'), findsOneWidget);
  });

  testWidgets('right swipe on attachment strip does not open drawer', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatContentDrawerSwipeHarness());

    final state = tester.state<_ChatContentDrawerSwipeHarnessState>(
      find.byType(_ChatContentDrawerSwipeHarness),
    );
    final beforeOffset = state.attachmentOffset;

    await tester.drag(
      find.byKey(const ValueKey('attachment-strip')),
      const Offset(120, 0),
    );
    await tester.pumpAndSettle();

    expect(find.text('home_drawer'), findsNothing);
    expect(state.attachmentOffset, lessThan(beforeOffset));
  });
}

class _ChatContentSurface extends StatefulWidget {
  const _ChatContentSurface();

  @override
  State<_ChatContentSurface> createState() => _ChatContentSurfaceState();
}

class _ChatContentSurfaceState extends State<_ChatContentSurface> {
  final ScrollController _horizontalScrollController = ScrollController(
    initialScrollOffset: 160,
  );

  double get horizontalOffset => _horizontalScrollController.offset;

  @override
  void dispose() {
    _horizontalScrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      key: const ValueKey('chat-content-surface'),
      color: const Color(0xFFF6F8FC),
      child: Column(
        children: [
          const SizedBox(height: 40),
          SizedBox(
            height: 72,
            child: ChatDrawerGestureGuard(
              child: ListView.separated(
                key: const ValueKey('content-horizontal-list'),
                controller: _horizontalScrollController,
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                itemCount: 8,
                separatorBuilder: (_, _) => const SizedBox(width: 8),
                itemBuilder: (_, index) {
                  return Container(
                    width: 140,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: const Color(0xFFEAF2FF),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text('list-item-$index'),
                  );
                },
              ),
            ),
          ),
          Container(
            key: const ValueKey('markdown-table-host'),
            margin: const EdgeInsets.fromLTRB(16, 20, 16, 0),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(12),
            ),
            child: const OmnibotMarkdownBody(
              data:
                  '| Name | Summary | Notes |\n'
                  '| --- | --- | --- |\n'
                  '| alpha | very_long_summary_value_that_requires_horizontal_scrolling_001 | note |\n'
                  '| beta | very_long_summary_value_that_requires_horizontal_scrolling_002 | note |',
              baseStyle: TextStyle(fontSize: 14, color: Color(0xFF0F172A)),
            ),
          ),
          const Expanded(child: SizedBox.expand()),
        ],
      ),
    );
  }
}
