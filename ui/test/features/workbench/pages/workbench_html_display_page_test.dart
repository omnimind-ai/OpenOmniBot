import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/workbench/pages/workbench_html_display_page.dart';

void main() {
  test('device-width viewport uses normal mobile WebView rendering', () {
    expect(
      workbenchHtmlUsesFixedCanvasViewport(
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
      ),
      isFalse,
    );
  });

  test('narrow numeric viewport is not treated as a wide canvas', () {
    expect(
      workbenchHtmlUsesFixedCanvasViewport(
        '<meta content="width=390, initial-scale=1" name="viewport">',
      ),
      isFalse,
    );
  });

  test('wide numeric viewport keeps fixed-canvas WebView rendering', () {
    expect(
      workbenchHtmlUsesFixedCanvasViewport(
        '<meta name="viewport" content="width=1280">',
      ),
      isTrue,
    );
  });
}
