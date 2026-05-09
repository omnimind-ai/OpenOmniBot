import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  const resolver = HdPadPaneLayoutResolver();

  test('requires a tablet-sized landscape viewport for HD pad mode', () {
    expect(isHdPadLandscapeViewport(const Size(932, 430)), isFalse);
    expect(isHdPadLandscapeViewport(const Size(844, 390)), isFalse);
    expect(isHdPadLandscapeViewport(const Size(959, 600)), isFalse);
    expect(isHdPadLandscapeViewport(const Size(768, 1024)), isFalse);
    expect(isHdPadLandscapeViewport(const Size(960, 600)), isTrue);
    expect(isHdPadLandscapeViewport(const Size(1024, 768)), isTrue);
  });

  test('uses defaults within supported width', () {
    final layout = resolver.resolve(1200);

    expect(layout.leftWidth, HdPadPaneLayoutResolver.defaultLeftWidth);
    expect(layout.rightWidth, HdPadPaneLayoutResolver.defaultRightWidth);
    expect(
      layout.centerWidth,
      1200 -
          HdPadPaneLayoutResolver.dividerHitWidth * 2 -
          HdPadPaneLayoutResolver.defaultLeftWidth -
          HdPadPaneLayoutResolver.defaultRightWidth,
    );
  });

  test('clamps oversized preferences to preserve minimum center width', () {
    final layout = resolver.resolve(
      960,
      preferredLeftWidth: 360,
      preferredRightWidth: 420,
    );

    expect(
      layout.leftWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minLeftWidth),
    );
    expect(
      layout.rightWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minRightWidth),
    );
    expect(
      layout.centerWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minCenterWidth),
    );
  });

  test('clamps saved widths to pane-specific bounds', () {
    final layout = resolver.resolve(
      1400,
      preferredLeftWidth: 120,
      preferredRightWidth: 1000,
    );

    expect(layout.leftWidth, HdPadPaneLayoutResolver.minLeftWidth);
    expect(
      layout.rightWidth,
      lessThanOrEqualTo(HdPadPaneLayoutResolver.maxRightWidth),
    );
    expect(
      layout.centerWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minCenterWidth),
    );
  });

  test('supports collapsing the left pane while keeping the right pane', () {
    final layout = resolver.resolve(
      1200,
      preferredLeftWidth: 320,
      preferredRightWidth: 300,
      collapseLeftPane: true,
    );

    expect(layout.leftWidth, 0);
    expect(layout.rightWidth, HdPadPaneLayoutResolver.defaultRightWidth);
    expect(
      layout.centerWidth,
      1200 -
          HdPadPaneLayoutResolver.dividerHitWidth -
          HdPadPaneLayoutResolver.defaultRightWidth,
    );
  });

  test('supports collapsing the right pane while keeping the left pane', () {
    final layout = resolver.resolve(
      1200,
      preferredLeftWidth: 260,
      preferredRightWidth: 360,
      collapseRightPane: true,
    );

    expect(layout.leftWidth, HdPadPaneLayoutResolver.defaultLeftWidth);
    expect(layout.rightWidth, 0);
    expect(
      layout.centerWidth,
      1200 -
          HdPadPaneLayoutResolver.dividerHitWidth -
          HdPadPaneLayoutResolver.defaultLeftWidth,
    );
  });

  test('supports collapsing both side panes', () {
    final layout = resolver.resolve(
      1200,
      collapseLeftPane: true,
      collapseRightPane: true,
    );

    expect(layout.leftWidth, 0);
    expect(layout.rightWidth, 0);
    expect(layout.centerWidth, 1200);
  });

  test('resolves overlay anchor from current keyboard spacing', () {
    final geometry = resolveChatPaneOverlayAnchorGeometry(
      viewportSize: const Size(420, 900),
      bottomSpacing: 260,
      anchorHeight: 96,
    );

    expect(geometry.bottom, 260);
    expect(geometry.rect.left, 24);
    expect(geometry.rect.width, 372);
    expect(geometry.rect.top, 640);
    expect(geometry.rect.height, 96);
  });

  test('clamps overlay anchor when keyboard spacing exceeds viewport', () {
    final geometry = resolveChatPaneOverlayAnchorGeometry(
      viewportSize: const Size(420, 300),
      bottomSpacing: 480,
      anchorHeight: 0,
    );

    expect(geometry.bottom, 300);
    expect(geometry.rect.top, 0);
    expect(geometry.rect.height, 0);
  });
}
