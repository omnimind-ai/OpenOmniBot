import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/theme/theme_context.dart';

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

class _ChatAppBarHarness extends StatefulWidget {
  const _ChatAppBarHarness();

  @override
  State<_ChatAppBarHarness> createState() => _ChatAppBarHarnessState();
}

class _ChatAppBarHarnessState extends State<_ChatAppBarHarness> {
  ChatIslandDisplayLayer _displayLayer = ChatIslandDisplayLayer.model;
  ChatSurfaceMode _activeMode = ChatSurfaceMode.normal;
  int _browserTapCount = 0;
  int _envTapCount = 0;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: Scaffold(
          body: Column(
            children: [
              ChatAppBar(
                onMenuTap: () {},
                onCompanionTap: () {},
                activeMode: _activeMode,
                onModeChanged: (value) {
                  setState(() {
                    _activeMode = value;
                  });
                },
                activeModelId: 'gpt-5.4',
                displayLayer: _displayLayer,
                onDisplayLayerChanged: (value) {
                  setState(() {
                    _displayLayer = value;
                  });
                },
                onTerminalEnvironmentTap: (_) {
                  setState(() {
                    _envTapCount += 1;
                  });
                },
                onTerminalTap: () {},
                onBrowserTap: () {
                  setState(() {
                    _browserTapCount += 1;
                  });
                },
                hasTerminalEnvironment: true,
                isBrowserEnabled: false,
                activeToolType: null,
              ),
              Text('layer:${_displayLayer.wireName}'),
              Text('browserTaps:$_browserTapCount'),
              Text('envTaps:$_envTapCount'),
            ],
          ),
        ),
      ),
    );
  }
}

class _PureChatToggleHarness extends StatefulWidget {
  const _PureChatToggleHarness({
    this.selected = false,
    this.locked = false,
    this.showAgentTapCount = false,
    this.showCodexTapCount = false,
  });

  final bool selected;
  final bool locked;
  final bool showAgentTapCount;
  final bool showCodexTapCount;

  @override
  State<_PureChatToggleHarness> createState() => _PureChatToggleHarnessState();
}

class _PureChatToggleHarnessState extends State<_PureChatToggleHarness> {
  late bool _selected = widget.selected;
  late final bool _locked = widget.locked;
  int _toggleCount = 0;
  int _agentTapCount = 0;
  int _codexTapCount = 0;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: Scaffold(
          body: Column(
            children: [
              ChatAppBar(
                onMenuTap: () {},
                onAgentTap: () {
                  setState(() {
                    _agentTapCount += 1;
                  });
                },
                onPureChatToggleTap: () {
                  setState(() {
                    _selected = !_selected;
                    _toggleCount += 1;
                  });
                },
                onCodexTap: () {
                  setState(() {
                    _codexTapCount += 1;
                  });
                },
                onCompanionTap: () {},
                activeMode: ChatSurfaceMode.normal,
                onModeChanged: (_) {},
                activeModelId: 'gpt-5.4',
                displayLayer: ChatIslandDisplayLayer.model,
                onDisplayLayerChanged: (_) {},
                onTerminalEnvironmentTap: (_) {},
                onTerminalTap: () {},
                onBrowserTap: () {},
                showPureChatToggle: true,
                isPureChatSelected: _selected,
                isPureChatToggleLocked: _locked,
              ),
              Text('selected:$_selected'),
              Text('locked:$_locked'),
              Text('toggles:$_toggleCount'),
              if (widget.showAgentTapCount) Text('agentTaps:$_agentTapCount'),
              if (widget.showCodexTapCount) Text('codexTaps:$_codexTapCount'),
            ],
          ),
        ),
      ),
    );
  }
}

class _SurfaceTransitionHarness extends StatefulWidget {
  const _SurfaceTransitionHarness({
    this.applyDelayByMode = const <ChatSurfaceMode, Duration>{},
  });

  final Map<ChatSurfaceMode, Duration> applyDelayByMode;

  @override
  State<_SurfaceTransitionHarness> createState() =>
      _SurfaceTransitionHarnessState();
}

class _SurfaceTransitionHarnessState extends State<_SurfaceTransitionHarness> {
  static const Duration _revealDelay = Duration(milliseconds: 1700);

  late final PageController _pageController = PageController(
    initialPage: _pageIndexForSurface(ChatSurfaceMode.openclaw),
  );
  ChatSurfaceMode _activeMode = ChatSurfaceMode.openclaw;
  ChatIslandDisplayLayer _normalDisplayLayer = ChatIslandDisplayLayer.model;
  Timer? _revealTimer;
  bool _revealInterrupted = false;
  bool _isSurfacePageScrolling = false;
  int _surfaceSwitchRequestId = 0;
  int? _pageGesturePointerId;
  double _pageVerticalDragDelta = 0;

  int _pageIndexForSurface(ChatSurfaceMode mode) => switch (mode) {
    ChatSurfaceMode.normal => 0,
    ChatSurfaceMode.workspace => 1,
    ChatSurfaceMode.openclaw => 2,
  };

  ChatSurfaceMode _surfaceForPageIndex(int pageIndex) => switch (pageIndex) {
    1 => ChatSurfaceMode.workspace,
    2 => ChatSurfaceMode.openclaw,
    _ => ChatSurfaceMode.normal,
  };

  void _cancelReveal() {
    _revealTimer?.cancel();
    _revealTimer = null;
  }

  void _interruptReveal() {
    _cancelReveal();
    _revealInterrupted = true;
  }

  void _forceNormalModeLayer() {
    _normalDisplayLayer = ChatIslandDisplayLayer.mode;
  }

  bool _canRevealModel() {
    return _activeMode == ChatSurfaceMode.normal &&
        !_isSurfacePageScrolling &&
        !_revealInterrupted &&
        _normalDisplayLayer == ChatIslandDisplayLayer.mode;
  }

  void _scheduleReveal() {
    _cancelReveal();
    if (!_canRevealModel()) {
      return;
    }
    _revealTimer = Timer(_revealDelay, () {
      _revealTimer = null;
      if (!mounted || !_canRevealModel()) {
        return;
      }
      setState(() {
        _normalDisplayLayer = ChatIslandDisplayLayer.model;
      });
    });
  }

  void _handleSurfaceScrollStart() {
    _cancelReveal();
    if (!mounted) {
      _isSurfacePageScrolling = true;
      _forceNormalModeLayer();
      return;
    }
    if (_isSurfacePageScrolling &&
        _normalDisplayLayer == ChatIslandDisplayLayer.mode) {
      return;
    }
    setState(() {
      _isSurfacePageScrolling = true;
      _forceNormalModeLayer();
    });
  }

  void _handleSurfaceScrollSettled(ChatSurfaceMode mode) {
    _cancelReveal();
    if (!mounted) {
      _isSurfacePageScrolling = false;
      if (mode == ChatSurfaceMode.normal) {
        _revealInterrupted = false;
        _forceNormalModeLayer();
      }
      return;
    }
    final shouldSetState =
        _isSurfacePageScrolling ||
        (mode == ChatSurfaceMode.normal &&
            _normalDisplayLayer != ChatIslandDisplayLayer.mode);
    if (shouldSetState) {
      setState(() {
        _isSurfacePageScrolling = false;
        if (mode == ChatSurfaceMode.normal) {
          _revealInterrupted = false;
          _forceNormalModeLayer();
        }
      });
    } else {
      _isSurfacePageScrolling = false;
      if (mode == ChatSurfaceMode.normal) {
        _revealInterrupted = false;
      }
    }
    if (mode == ChatSurfaceMode.normal) {
      _scheduleReveal();
    }
  }

  bool _handleScrollNotification(ScrollNotification notification) {
    if (notification.depth != 0 ||
        notification.metrics.axis != Axis.horizontal) {
      return false;
    }
    if (notification is ScrollStartNotification) {
      _handleSurfaceScrollStart();
      return false;
    }
    if (notification is UserScrollNotification) {
      final direction = notification.direction;
      if (direction == ScrollDirection.forward ||
          direction == ScrollDirection.reverse) {
        _handleSurfaceScrollStart();
      }
      return false;
    }
    if (notification is ScrollEndNotification) {
      final pageMetrics = notification.metrics;
      final rawPage = pageMetrics is PageMetrics
          ? pageMetrics.page
          : (_pageController.hasClients ? _pageController.page : null);
      final settledIndex =
          (rawPage ?? _pageIndexForSurface(_activeMode).toDouble()).round();
      _handleSurfaceScrollSettled(_surfaceForPageIndex(settledIndex));
    }
    return false;
  }

  Future<void> _switchMode(
    ChatSurfaceMode targetMode, {
    bool syncPage = true,
  }) async {
    final requestId = ++_surfaceSwitchRequestId;
    bool isStaleRequest() => !mounted || requestId != _surfaceSwitchRequestId;
    if (_activeMode == targetMode) {
      if (targetMode == ChatSurfaceMode.normal && !syncPage) {
        _scheduleReveal();
      }
      return;
    }

    _cancelReveal();
    final delay = widget.applyDelayByMode[targetMode] ?? Duration.zero;
    if (delay > Duration.zero) {
      await Future<void>.delayed(delay);
    }
    if (isStaleRequest()) {
      return;
    }

    setState(() {
      _activeMode = targetMode;
      if (targetMode == ChatSurfaceMode.normal) {
        _revealInterrupted = false;
        _forceNormalModeLayer();
      }
    });
    if (syncPage) {
      await _pageController.animateToPage(
        _pageIndexForSurface(targetMode),
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
      return;
    }
    if (targetMode == ChatSurfaceMode.normal && !_isSurfacePageScrolling) {
      _scheduleReveal();
    }
  }

  @override
  void dispose() {
    _cancelReveal();
    _pageController.dispose();
    super.dispose();
  }

  void _handlePagePointerDown(PointerDownEvent event) {
    _pageGesturePointerId = event.pointer;
    _pageVerticalDragDelta = 0;
  }

  void _handlePagePointerMove(PointerMoveEvent event) {
    if (event.pointer != _pageGesturePointerId ||
        _activeMode != ChatSurfaceMode.normal) {
      return;
    }
    _pageVerticalDragDelta += event.delta.dy;
    if (_revealTimer != null &&
        !_revealInterrupted &&
        _pageVerticalDragDelta.abs() >= 6) {
      _interruptReveal();
    }
  }

  void _handlePagePointerUp(PointerUpEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    if (_activeMode == ChatSurfaceMode.normal &&
        _pageVerticalDragDelta.abs() >= 18) {
      setState(() {
        _normalDisplayLayer = _pageVerticalDragDelta > 0
            ? ChatIslandDisplayLayer.tools
            : ChatIslandDisplayLayer.model;
      });
    }
    _pageGesturePointerId = null;
    _pageVerticalDragDelta = 0;
  }

  void _handlePagePointerCancel(PointerCancelEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    _pageGesturePointerId = null;
    _pageVerticalDragDelta = 0;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: Scaffold(
          body: Column(
            children: [
              ChatAppBar(
                onMenuTap: () {},
                onCompanionTap: () {},
                activeMode: _activeMode,
                onModeChanged: (value) {
                  _switchMode(value);
                },
                activeModelId: 'gpt-5.4',
                displayLayer: _activeMode == ChatSurfaceMode.normal
                    ? _normalDisplayLayer
                    : ChatIslandDisplayLayer.mode,
                onInteracted: _cancelReveal,
                onDisplayLayerChanged: (value) {
                  _cancelReveal();
                  setState(() {
                    _normalDisplayLayer = value;
                  });
                },
                onTerminalEnvironmentTap: (_) {},
                onTerminalTap: () {},
                onBrowserTap: () {},
                hasTerminalEnvironment: false,
                isBrowserEnabled: true,
                activeToolType: null,
              ),
              Text('active:${_activeMode.name}'),
              Text('layer:${_normalDisplayLayer.wireName}'),
              TextButton(
                key: const ValueKey('request-normal'),
                onPressed: () {
                  _switchMode(ChatSurfaceMode.normal, syncPage: false);
                },
                child: const Text('request-normal'),
              ),
              TextButton(
                key: const ValueKey('request-openclaw'),
                onPressed: () {
                  _switchMode(ChatSurfaceMode.openclaw, syncPage: false);
                },
                child: const Text('request-openclaw'),
              ),
              TextButton(
                key: const ValueKey('simulate-page-scroll'),
                onPressed: () {
                  _interruptReveal();
                },
                child: const Text('simulate-page-scroll'),
              ),
              Expanded(
                child: Listener(
                  behavior: HitTestBehavior.translucent,
                  onPointerDown: _handlePagePointerDown,
                  onPointerMove: _handlePagePointerMove,
                  onPointerUp: _handlePagePointerUp,
                  onPointerCancel: _handlePagePointerCancel,
                  child: NotificationListener<ScrollNotification>(
                    onNotification: _handleScrollNotification,
                    child: PageView(
                      controller: _pageController,
                      onPageChanged: (pageIndex) {
                        _switchMode(
                          _surfaceForPageIndex(pageIndex),
                          syncPage: false,
                        );
                      },
                      children: const [
                        ColoredBox(color: Colors.white),
                        ColoredBox(color: Colors.white),
                        ColoredBox(color: Colors.white),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _FloatingPanelGestureExclusionHarness extends StatefulWidget {
  const _FloatingPanelGestureExclusionHarness();

  @override
  State<_FloatingPanelGestureExclusionHarness> createState() =>
      _FloatingPanelGestureExclusionHarnessState();
}

class _FloatingPanelGestureExclusionHarnessState
    extends State<_FloatingPanelGestureExclusionHarness> {
  final GlobalKey _panelKey = GlobalKey();
  ChatIslandDisplayLayer _displayLayer = ChatIslandDisplayLayer.model;
  int? _pageGesturePointerId;
  double _pageVerticalDragDelta = 0;

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

  void _handlePagePointerDown(PointerDownEvent event) {
    if (_isPointerInside(_panelKey, event.position)) {
      _pageGesturePointerId = null;
      _pageVerticalDragDelta = 0;
      return;
    }
    _pageGesturePointerId = event.pointer;
    _pageVerticalDragDelta = 0;
  }

  void _handlePagePointerMove(PointerMoveEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    _pageVerticalDragDelta += event.delta.dy;
  }

  void _handlePagePointerUp(PointerUpEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    if (_pageVerticalDragDelta.abs() >= 18) {
      setState(() {
        _displayLayer = _pageVerticalDragDelta > 0
            ? ChatIslandDisplayLayer.tools
            : ChatIslandDisplayLayer.model;
      });
    }
    _pageGesturePointerId = null;
    _pageVerticalDragDelta = 0;
  }

  void _handlePagePointerCancel(PointerCancelEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    _pageGesturePointerId = null;
    _pageVerticalDragDelta = 0;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        body: Column(
          children: [
            Text('layer:${_displayLayer.wireName}'),
            Expanded(
              child: Listener(
                behavior: HitTestBehavior.translucent,
                onPointerDown: _handlePagePointerDown,
                onPointerMove: _handlePagePointerMove,
                onPointerUp: _handlePagePointerUp,
                onPointerCancel: _handlePagePointerCancel,
                child: Stack(
                  children: [
                    Positioned.fill(
                      child: ColoredBox(
                        key: const ValueKey('floating-background'),
                        color: const Color(0xFFF5F7FB),
                      ),
                    ),
                    Align(
                      alignment: Alignment.bottomCenter,
                      child: Padding(
                        padding: const EdgeInsets.only(bottom: 32),
                        child: Material(
                          key: _panelKey,
                          elevation: 4,
                          borderRadius: BorderRadius.circular(16),
                          color: Colors.white,
                          child: SizedBox(
                            width: 240,
                            height: 180,
                            child: ListView.builder(
                              key: const ValueKey('floating-panel'),
                              padding: const EdgeInsets.symmetric(vertical: 8),
                              physics: const ClampingScrollPhysics(),
                              itemCount: 12,
                              itemBuilder: (context, index) {
                                return ListTile(
                                  dense: true,
                                  title: Text('model-$index'),
                                );
                              },
                            ),
                          ),
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

Future<void> _tapModeSegment(WidgetTester tester, int index) async {
  final slider = find.byType(ChatModeSlider);
  final box = tester.renderObject<RenderBox>(slider);
  final topLeft = box.localToGlobal(Offset.zero);
  final segmentWidth = box.size.width / 2;
  final tapOffset =
      topLeft + Offset(segmentWidth * (index + 0.5), box.size.height / 2);
  await tester.tapAt(tapOffset);
}

Future<void> _pumpSurfaceSwitch(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 260));
}

void _setTestViewport(WidgetTester tester, Size size) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
}

void main() {
  testWidgets('keeps model layer visible by default in normal chat', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    expect(find.text('layer:model'), findsOneWidget);
    expect(find.text('gpt-5.4'), findsOneWidget);
  });

  testWidgets('swaps companion shortcut left and mode menu right', (
    tester,
  ) async {
    await tester.pumpWidget(const _PureChatToggleHarness());

    final menuRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-menu-button')),
    );
    final companionRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-companion-button')),
    );
    final modeMenuRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    final islandRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-island')),
    );
    final companionCenter = companionRect.center;
    final expectedGapMidpoint = (menuRect.right + islandRect.left) / 2;

    expect(menuRect.right, lessThan(companionRect.left));
    expect(companionRect.right, lessThan(islandRect.left));
    expect(companionCenter.dx, closeTo(expectedGapMidpoint, 1));
    expect(islandRect.right, lessThan(modeMenuRect.left));
  });

  testWidgets('uses page background when surface switcher is visible', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onCompanionTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              activeModelId: 'gpt-5.4',
              displayLayer: ChatIslandDisplayLayer.model,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
            ),
          ),
        ),
      ),
    );

    final appBarContext = tester.element(find.byType(ChatAppBar));
    final rootSurface = tester.widget<ColoredBox>(
      find.byKey(const ValueKey('chat-app-bar-background')),
    );

    expect(rootSurface.color, appBarContext.omniPalette.pageBackground);
  });

  testWidgets('shows workspace restore button before the right mode menu', (
    tester,
  ) async {
    var tapCount = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onCompanionTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              activeModelId: 'gpt-5.4',
              displayLayer: ChatIslandDisplayLayer.model,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              showPureChatToggle: true,
              showWorkspacePaneButton: true,
              onWorkspacePaneTap: () {
                tapCount += 1;
              },
            ),
          ),
        ),
      ),
    );

    final workspaceButton = find.byKey(
      const ValueKey('chat-app-bar-workspace-pane-button'),
    );
    final island = find.byKey(const ValueKey('chat-app-bar-island'));
    final modeMenu = find.byKey(
      const ValueKey('chat-app-bar-pure-chat-button'),
    );
    final workspaceRect = tester.getRect(workspaceButton);
    final islandRect = tester.getRect(island);
    final modeMenuRect = tester.getRect(modeMenu);

    expect(workspaceButton, findsOneWidget);
    expect(islandRect.right, lessThan(workspaceRect.left));
    expect(workspaceRect.right, lessThanOrEqualTo(modeMenuRect.left));

    await tester.tap(workspaceButton);
    expect(tapCount, 1);
  });

  testWidgets('keeps swapped shortcuts clear of island on narrow screens', (
    tester,
  ) async {
    _setTestViewport(tester, const Size(390, 844));
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const _PureChatToggleHarness());

    final companionRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-companion-button')),
    );
    final modeMenuRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    final islandRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-island')),
    );

    expect(companionRect.right, lessThanOrEqualTo(islandRect.left));
    expect(islandRect.right, lessThanOrEqualTo(modeMenuRect.left));
  });

  testWidgets('highlights pure chat toggle when selected', (tester) async {
    await tester.pumpWidget(const _PureChatToggleHarness(selected: true));

    final pureChatButton = find.byKey(
      const ValueKey('chat-app-bar-pure-chat-button'),
    );
    final pureChatIcon = tester.widget<SvgPicture>(
      find.descendant(of: pureChatButton, matching: find.byType(SvgPicture)),
    );

    expect(
      pureChatIcon.colorFilter,
      const ColorFilter.mode(Color(0xFF2C7FEB), BlendMode.srcIn),
    );
  });

  testWidgets('respects pure chat toggle lock flag', (tester) async {
    await tester.pumpWidget(const _PureChatToggleHarness(locked: true));

    expect(find.text('selected:false'), findsOneWidget);
    expect(find.text('toggles:0'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    expect(find.text('selected:false'), findsOneWidget);
    expect(find.text('toggles:0'), findsOneWidget);
  });

  testWidgets('opens mode menu with agent codex and pure chat actions', (
    tester,
  ) async {
    await tester.pumpWidget(
      const _PureChatToggleHarness(
        showAgentTapCount: true,
        showCodexTapCount: true,
      ),
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-agent')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-codex')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
      findsOneWidget,
    );
    final pureChatMenuIcon = tester.widget<SvgPicture>(
      find.descendant(
        of: find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
        matching: find.byType(SvgPicture),
      ),
    );
    expect(pureChatMenuIcon.width, 18);
    expect(pureChatMenuIcon.height, 18);
    expect(find.text('Agent 模式'), findsNothing);
    expect(find.text('Codex 模式'), findsNothing);
    expect(find.text('纯聊天模式'), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-agent')),
    );
    await tester.pumpAndSettle();

    expect(find.text('agentTaps:1'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-codex')),
    );
    await tester.pumpAndSettle();

    expect(find.text('codexTaps:1'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
    );
    await tester.pumpAndSettle();

    expect(find.text('selected:true'), findsOneWidget);
    expect(find.text('toggles:1'), findsOneWidget);
  });

  testWidgets('uses chat-left workspace-right surface order', (tester) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await _tapModeSegment(tester, 1);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:workspace'), findsOneWidget);

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:normal'), findsOneWidget);
  });

  testWidgets('content swipe matches chat-left workspace-right order', (
    tester,
  ) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:normal'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(-640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:workspace'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:normal'), findsOneWidget);
  });

  testWidgets('shows update indicator next to mode menu without direct codex', (
    tester,
  ) async {
    var tapCount = 0;
    var codexTapCount = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onCompanionTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              activeModelId: 'gpt-5.4',
              displayLayer: ChatIslandDisplayLayer.model,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              showAppUpdateIndicator: true,
              showPureChatToggle: true,
              appUpdateTooltip: '发现新版本 v9.9.9',
              onCodexTap: () {
                codexTapCount += 1;
              },
              onAppUpdateTap: () {
                tapCount += 1;
              },
            ),
          ),
        ),
      ),
    );

    final indicator = find.byKey(const ValueKey('chat-app-update-button'));
    final codex = find.byKey(const ValueKey('chat-app-codex-button'));
    final modeMenu = find.byKey(
      const ValueKey('chat-app-bar-pure-chat-button'),
    );
    final companion = find.byKey(const ValueKey('chat-app-companion-button'));
    final island = find.byKey(const ValueKey('chat-app-bar-island'));
    expect(indicator, findsOneWidget);
    expect(codex, findsNothing);
    expect(modeMenu, findsOneWidget);
    expect(companion, findsOneWidget);

    expect(
      tester.getRect(companion).right,
      lessThanOrEqualTo(tester.getRect(island).left),
    );
    expect(
      tester.getRect(indicator).right,
      lessThanOrEqualTo(tester.getRect(modeMenu).left),
    );

    await tester.tap(indicator);
    await tester.pumpAndSettle();

    expect(tapCount, 1);

    await tester.tap(modeMenu);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-codex')),
    );
    await tester.pumpAndSettle();

    expect(codexTapCount, 1);
  });

  testWidgets('tints and enlarges codex icon with theme color when selected', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onCompanionTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              activeModelId: 'gpt-5.4',
              displayLayer: ChatIslandDisplayLayer.model,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              isCodexReady: true,
              isCodexConnected: true,
              isCodexSelected: true,
              showPureChatToggle: true,
            ),
          ),
        ),
      ),
    );

    final codex = find.byKey(const ValueKey('chat-app-bar-pure-chat-button'));
    final codexIcon = tester.widget<SvgPicture>(
      find.descendant(of: codex, matching: find.byType(SvgPicture)),
    );

    expect(
      codexIcon.colorFilter,
      const ColorFilter.mode(Color(0xFF2C7FEB), BlendMode.srcIn),
    );
    expect(codexIcon.width, 22);
    expect(codexIcon.height, 22);
  });

  testWidgets('uses current chat mode icon in surface slider', (tester) async {
    Future<void> pumpAppBar({
      bool isAgentSelected = false,
      bool isCodexSelected = false,
      bool isPureChatSelected = false,
    }) async {
      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: Scaffold(
              body: ChatAppBar(
                onMenuTap: () {},
                onCompanionTap: () {},
                activeMode: ChatSurfaceMode.normal,
                onModeChanged: (_) {},
                activeModelId: 'gpt-5.4',
                displayLayer: ChatIslandDisplayLayer.mode,
                onDisplayLayerChanged: (_) {},
                onTerminalEnvironmentTap: (_) {},
                onTerminalTap: () {},
                onBrowserTap: () {},
                isAgentSelected: isAgentSelected,
                isCodexSelected: isCodexSelected,
                isPureChatSelected: isPureChatSelected,
              ),
            ),
          ),
        ),
      );
      await tester.pump();
    }

    String primaryIconAsset() {
      final icon = tester.widget<SvgPicture>(
        find.byKey(const ValueKey('chat-mode-slider-primary-icon')),
      );
      return icon.bytesLoader.toString();
    }

    await pumpAppBar(isAgentSelected: true);
    expect(primaryIconAsset(), contains('assets/home/chat/agent.svg'));

    await pumpAppBar(isCodexSelected: true);
    expect(primaryIconAsset(), contains('assets/home/chat/codex.svg'));

    await pumpAppBar(isPureChatSelected: true);
    expect(primaryIconAsset(), contains('assets/home/chat/pure_chat.svg'));
  });

  testWidgets('supports direct island swipe between model and tools layers', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    await tester.drag(find.text('gpt-5.4'), const Offset(0, -42));
    await tester.pumpAndSettle();

    expect(find.text('layer:model'), findsOneWidget);
    expect(find.text('gpt-5.4'), findsOneWidget);

    await tester.drag(find.text('gpt-5.4'), const Offset(0, 42));
    await tester.pumpAndSettle();

    expect(find.text('layer:tools'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('chat-island-terminal-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-island-terminal-env-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-island-browser-button')),
      findsOneWidget,
    );

    final envWidth = tester
        .renderObject<RenderBox>(
          find.byKey(const ValueKey('chat-island-terminal-env-button')),
        )
        .size
        .width;
    final terminalWidth = tester
        .renderObject<RenderBox>(
          find.byKey(const ValueKey('chat-island-terminal-button')),
        )
        .size
        .width;
    final browserWidth = tester
        .renderObject<RenderBox>(
          find.byKey(const ValueKey('chat-island-browser-button')),
        )
        .size
        .width;

    expect(envWidth, moreOrLessEquals(terminalWidth, epsilon: 0.1));
    expect(envWidth, moreOrLessEquals(browserWidth, epsilon: 0.1));

    await tester.tap(
      find.byKey(const ValueKey('chat-island-terminal-env-button')),
    );
    await tester.pumpAndSettle();

    expect(find.text('envTaps:1'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('chat-island-browser-button')));
    await tester.pumpAndSettle();

    expect(find.text('browserTaps:0'), findsOneWidget);

    await tester.drag(
      find.byKey(const ValueKey('chat-island-terminal-button')),
      const Offset(0, -42),
    );
    await tester.pumpAndSettle();

    expect(find.text('layer:model'), findsOneWidget);
  });

  testWidgets('ignores floating panel drags for island layer switching', (
    tester,
  ) async {
    await tester.pumpWidget(const _FloatingPanelGestureExclusionHarness());

    expect(find.text('layer:model'), findsOneWidget);

    await tester.drag(
      find.byKey(const ValueKey('floating-panel')),
      const Offset(0, 64),
    );
    await tester.pumpAndSettle();

    expect(find.text('layer:model'), findsOneWidget);

    final background = find.byKey(const ValueKey('floating-background'));
    await tester.dragFrom(
      tester.getTopLeft(background) + const Offset(40, 40),
      const Offset(0, 64),
    );
    await tester.pumpAndSettle();

    expect(find.text('layer:tools'), findsOneWidget);
  });

  testWidgets('hides surface switcher when disabled', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onCompanionTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              activeModelId: 'gpt-5.4',
              displayLayer: ChatIslandDisplayLayer.mode,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              showMenuButton: false,
              showSurfaceSwitcher: false,
            ),
          ),
        ),
      ),
    );

    expect(find.byType(ChatModeSlider), findsNothing);
    expect(find.text('gpt-5.4'), findsOneWidget);

    final appBarContext = tester.element(find.byType(ChatAppBar));
    final rootSurface = tester.widget<ColoredBox>(
      find.byKey(const ValueKey('chat-app-bar-background')),
    );

    expect(rootSurface.color, appBarContext.omniPalette.surfacePrimary);
  });

  testWidgets(
    'reveals model only after normal surface settles and stays idle',
    (tester) async {
      await tester.pumpWidget(const _SurfaceTransitionHarness());

      expect(find.text('active:openclaw'), findsOneWidget);

      await _tapModeSegment(tester, 0);
      await _pumpSurfaceSwitch(tester);

      expect(find.text('active:normal'), findsOneWidget);
      expect(find.text('layer:mode'), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 1699));
      expect(find.text('layer:mode'), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 1));
      expect(find.text('layer:model'), findsOneWidget);
    },
  );

  testWidgets('resets reveal delay after repeated surface switches', (
    tester,
  ) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await tester.tap(find.byKey(const ValueKey('request-normal')));
    await tester.pump();
    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 1000));

    await tester.tap(find.byKey(const ValueKey('request-openclaw')));
    await tester.pump();
    expect(find.text('active:openclaw'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('request-normal')));
    await tester.pump();
    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 1699));
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 1));
    expect(find.text('layer:model'), findsOneWidget);
  });

  testWidgets('interrupts delayed reveal when page scroll happens in time', (
    tester,
  ) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 600));
    await tester.tap(find.byKey(const ValueKey('simulate-page-scroll')));
    await tester.pump();

    expect(find.text('layer:mode'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 2000));
    expect(find.text('layer:mode'), findsOneWidget);
  });

  testWidgets('ignores stale async surface switch requests', (tester) async {
    await tester.pumpWidget(
      const _SurfaceTransitionHarness(
        applyDelayByMode: <ChatSurfaceMode, Duration>{
          ChatSurfaceMode.normal: Duration(milliseconds: 120),
        },
      ),
    );

    await tester.tap(find.byKey(const ValueKey('request-normal')));
    await tester.pump(const Duration(milliseconds: 10));
    await tester.tap(find.byKey(const ValueKey('request-openclaw')));
    await tester.pump(const Duration(milliseconds: 140));

    expect(find.text('active:openclaw'), findsOneWidget);
    expect(find.text('layer:model'), findsOneWidget);
  });
}
