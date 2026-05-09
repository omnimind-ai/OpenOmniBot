import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/services/link_preview_service.dart';

void main() {
  test('extractUrls ignores omnibot resource urls', () {
    final service = LinkPreviewService();

    final urls = service.extractUrls(
      '先看 omnibot://workspace/demo/index.html 再看 https://example.com/docs',
    );

    expect(urls, <String>['https://example.com/docs']);
  });

  test('reconcilePreviewMaps keeps only web previews', () {
    final service = LinkPreviewService();

    final previews = service.reconcilePreviewMaps(
      text:
          '资源 [报告](omnibot://workspace/demo/report.html) 和网页 https://example.com/news',
    );

    expect(previews, hasLength(1));
    expect(
      ChatLinkPreview.fromJson(previews.single).url,
      'https://example.com/news',
    );
  });

  test('extractUrls ignores filenames inside markdown image syntax', () {
    final service = LinkPreviewService();

    final urls = service.extractUrls(
      '![screenshot_tab_1_1777050057660_1e40e3cd.jpg](omnibot://browser/f6d26871-5b43-4ba0-af49-7c09396569a8/screenshot_tab_1_1777050057660_1e40e3cd.jpg)',
    );

    expect(urls, isEmpty);
  });

  test('extractUrls ignores dot commands without common domain suffixes', () {
    final service = LinkPreviewService();

    final urls = service.extractUrls(
      '先执行 diagnostics.getprop 和 settings_control.get，再打开 github.com/docs',
    );

    expect(urls, <String>['https://github.com/docs']);
  });

  test('extractUrls keeps common multi-part domain suffixes', () {
    final service = LinkPreviewService();

    final urls = service.extractUrls(
      '文档在 example.co.uk/guide 和 docs.github.io/reference',
    );

    expect(urls, <String>[
      'https://example.co.uk/guide',
      'https://docs.github.io/reference',
    ]);
  });
}
