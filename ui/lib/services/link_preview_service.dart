import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:ui/models/chat_link_preview.dart';

class _UrlCandidate {
  const _UrlCandidate(this.index, this.raw);

  final int index;
  final String raw;
}

class LinkPreviewService {
  LinkPreviewService({http.Client? client}) : _client = client ?? http.Client();

  static LinkPreviewService _instance = LinkPreviewService();

  static LinkPreviewService get instance => _instance;

  @visibleForTesting
  static void debugSetInstance(LinkPreviewService service) {
    _instance = service;
  }

  @visibleForTesting
  static void debugResetInstance() {
    _instance = LinkPreviewService();
  }

  static const int maxPreviewsPerMessage = 3;
  static const Set<String> _commonBareDomainSuffixes = <String>{
    'com',
    'net',
    'org',
    'io',
    'ai',
    'app',
    'dev',
    'cn',
    'cc',
    'me',
    'tv',
    'fm',
    'xyz',
    'info',
    'top',
    'tech',
    'site',
    'online',
    'cloud',
    'shop',
    'store',
    'blog',
    'pro',
    'biz',
    'name',
    'edu',
    'gov',
    'mil',
    'int',
    'us',
    'uk',
    'ca',
    'au',
    'eu',
    'de',
    'fr',
    'jp',
    'kr',
    'sg',
    'hk',
    'tw',
    'in',
    'br',
    'ru',
    'it',
    'es',
    'nl',
    'co.uk',
    'org.uk',
    'ac.uk',
    'gov.uk',
    'com.cn',
    'net.cn',
    'org.cn',
    'gov.cn',
    'edu.cn',
    'co.jp',
    'ne.jp',
    'or.jp',
    'com.au',
    'net.au',
    'org.au',
    'com.hk',
    'com.tw',
    'com.sg',
    'com.br',
  };

  final http.Client _client;
  final Map<String, ChatLinkPreview> _memoryCache = <String, ChatLinkPreview>{};

  // 同一个 URL 在连续重建时只发起一次网络请求，避免重复抓取。
  final Map<String, Future<ChatLinkPreview>> _inFlight =
      <String, Future<ChatLinkPreview>>{};

  List<String> extractUrls(
    String text, {
    int maxCount = maxPreviewsPerMessage,
  }) {
    if (text.trim().isEmpty || maxCount <= 0) {
      return const <String>[];
    }

    final sanitizedText = _maskIgnoredSchemeUrls(text);
    final matches = <_UrlCandidate>[
      ...RegExp(r'https?://[^\s<>"\]\[]+', caseSensitive: false)
          .allMatches(sanitizedText)
          .map((match) => _UrlCandidate(match.start, match.group(0) ?? '')),
      ...RegExp(
            r'(?:www\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z][a-z0-9-]{1,62}(?:/[^\s<>"\]\[]*)?',
            caseSensitive: false,
          )
          .allMatches(sanitizedText)
          .where((match) {
            final previous = match.start > 0
                ? sanitizedText[match.start - 1]
                : '';
            return previous != '@' &&
                previous != '/' &&
                previous != '.' &&
                previous != '_' &&
                previous != '-';
          })
          .map((match) => _UrlCandidate(match.start, match.group(0) ?? '')),
    ]..sort((left, right) => left.index.compareTo(right.index));
    final urls = <String>[];
    final seen = <String>{};

    for (final match in matches) {
      final raw = match.raw;
      if (raw.isEmpty) {
        continue;
      }
      // 消息文本经常用 Markdown 包住链接，先清洗尾部符号再去重。
      final normalized = _normalizeExtractedUrl(raw);
      if (normalized == null || _looksLikeImageUrl(normalized)) {
        continue;
      }
      final previewKey = _canonicalPreviewKey(normalized);
      if (previewKey == null || seen.contains(previewKey)) {
        continue;
      }
      final uri = Uri.tryParse(normalized);
      if (uri == null ||
          !(uri.scheme == 'http' || uri.scheme == 'https') ||
          uri.host.trim().isEmpty) {
        continue;
      }
      seen.add(previewKey);
      urls.add(normalized);
      if (urls.length >= maxCount) {
        break;
      }
    }

    return urls;
  }

  String _maskIgnoredSchemeUrls(String text) {
    return text.replaceAllMapped(
      RegExp(r'omnibot://[^\s<>"\]\[)]+', caseSensitive: false),
      (match) => ''.padLeft(match.group(0)?.length ?? 0),
    );
  }

  List<Map<String, dynamic>> reconcilePreviewMaps({
    required String text,
    dynamic existing,
    int maxCount = maxPreviewsPerMessage,
  }) {
    final urls = extractUrls(text, maxCount: maxCount);
    if (urls.isEmpty) {
      return const <Map<String, dynamic>>[];
    }

    final existingPreviews = _parsePreviewMaps(existing);
    final existingByUrl = _previewByCanonicalUrl(existingPreviews);

    // 保留历史里已完成/失败的预览，只给新链接创建 loading 占位。
    return urls.map((url) {
      final previewKey = _canonicalPreviewKey(url);
      final preview =
          (previewKey == null ? null : existingByUrl[previewKey]) ??
          (previewKey == null ? null : _cachedPreviewForKey(previewKey)) ??
          ChatLinkPreview.loading(url);
      return preview.toJson();
    }).toList();
  }

  Future<ChatLinkPreview> loadPreview(String url) {
    final cached = _memoryCache[url];
    if (cached != null && cached.status != ChatLinkPreview.statusLoading) {
      return Future<ChatLinkPreview>.value(cached);
    }

    final existing = _inFlight[url];
    if (existing != null) {
      return existing;
    }

    final future = _fetchPreview(url);
    _inFlight[url] = future;
    future.whenComplete(() {
      _inFlight.remove(url);
    });
    return future;
  }

  Future<ChatLinkPreview> _fetchPreview(String url) async {
    try {
      final uri = Uri.parse(url);
      final response = await _client
          .get(
            uri,
            headers: const <String, String>{
              'accept': 'text/html,application/xhtml+xml',
            },
          )
          .timeout(const Duration(seconds: 8));
      if (response.statusCode < 200 || response.statusCode >= 400) {
        return _storeAndReturn(ChatLinkPreview.failed(url));
      }

      final html = utf8.decode(response.bodyBytes, allowMalformed: true);
      final preview = _parseHtml(url, html);
      return _storeAndReturn(preview);
    } catch (_) {
      return _storeAndReturn(ChatLinkPreview.failed(url));
    }
  }

  ChatLinkPreview _storeAndReturn(ChatLinkPreview preview) {
    _memoryCache[preview.url] = preview;
    return preview;
  }

  Map<String, ChatLinkPreview> _previewByCanonicalUrl(
    List<ChatLinkPreview> previews,
  ) {
    final result = <String, ChatLinkPreview>{};
    for (final preview in previews) {
      final key = _canonicalPreviewKey(preview.url);
      if (key == null || result.containsKey(key)) {
        continue;
      }
      result[key] = preview;
    }
    return result;
  }

  ChatLinkPreview? _cachedPreviewForKey(String key) {
    for (final entry in _memoryCache.entries) {
      if (_canonicalPreviewKey(entry.key) == key) {
        return entry.value;
      }
    }
    return null;
  }

  ChatLinkPreview _parseHtml(String url, String html) {
    final uri = Uri.parse(url);
    final ogTitle = _extractMetaContent(html, 'property', 'og:title');
    final ogDescription = _extractMetaContent(
      html,
      'property',
      'og:description',
    );
    final ogImage = _extractMetaContent(html, 'property', 'og:image');
    final ogSiteName = _extractMetaContent(html, 'property', 'og:site_name');
    final twitterTitle = _extractMetaContent(html, 'name', 'twitter:title');
    final twitterDescription = _extractMetaContent(
      html,
      'name',
      'twitter:description',
    );
    final twitterImage = _extractMetaContent(html, 'name', 'twitter:image');
    final pageTitle = _extractTagText(html, 'title');
    final metaDescription = _extractMetaContent(html, 'name', 'description');

    // 解析优先级：Open Graph > Twitter Card > 普通 HTML 标题/描述。
    return ChatLinkPreview(
      url: url,
      domain: uri.host,
      siteName: ogSiteName,
      title: ogTitle.isNotEmpty
          ? ogTitle
          : (twitterTitle.isNotEmpty ? twitterTitle : pageTitle),
      description: ogDescription.isNotEmpty
          ? ogDescription
          : (twitterDescription.isNotEmpty
                ? twitterDescription
                : metaDescription),
      imageUrl: _resolveLinkedUrl(
        uri,
        ogImage.isNotEmpty ? ogImage : twitterImage,
      ),
      status: ChatLinkPreview.statusReady,
    );
  }

  String _extractMetaContent(String html, String attribute, String name) {
    for (final tag in RegExp(
      r'<meta\b[^>]*>',
      caseSensitive: false,
      dotAll: true,
    ).allMatches(html)) {
      final rawTag = tag.group(0);
      if (rawTag == null || rawTag.isEmpty) {
        continue;
      }
      final attrs = _parseAttributes(rawTag);
      final key = (attrs[attribute] ?? '').trim().toLowerCase();
      if (key != name) {
        continue;
      }
      final content = _collapseWhitespace(
        _decodeHtmlEntities(attrs['content'] ?? ''),
      );
      if (content.isNotEmpty) {
        return content;
      }
    }
    return '';
  }

  String _extractTagText(String html, String tagName) {
    final match = RegExp(
      '<$tagName\\b[^>]*>(.*?)</$tagName>',
      caseSensitive: false,
      dotAll: true,
    ).firstMatch(html);
    if (match == null) {
      return '';
    }
    return _collapseWhitespace(_decodeHtmlEntities(match.group(1) ?? ''));
  }

  Map<String, String> _parseAttributes(String rawTag) {
    final attributes = <String, String>{};
    for (final match in RegExp(
      r'''([^\s=/>]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))''',
      caseSensitive: false,
    ).allMatches(rawTag)) {
      final key = (match.group(1) ?? '').trim().toLowerCase();
      final value = match.group(3) ?? match.group(4) ?? match.group(5) ?? '';
      if (key.isEmpty) {
        continue;
      }
      attributes[key] = value.trim();
    }
    return attributes;
  }

  List<ChatLinkPreview> _parsePreviewMaps(dynamic raw) {
    if (raw is! List) {
      return const <ChatLinkPreview>[];
    }
    return raw
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .map(ChatLinkPreview.fromJson)
        .toList();
  }

  String _resolveLinkedUrl(Uri baseUri, String rawValue) {
    final trimmed = rawValue.trim();
    if (trimmed.isEmpty) {
      return '';
    }
    final parsed = Uri.tryParse(trimmed);
    if (parsed == null) {
      return '';
    }
    return (parsed.hasScheme ? parsed : baseUri.resolveUri(parsed)).toString();
  }

  String? _normalizeExtractedUrl(String raw) {
    var candidate = raw.trim();
    if (candidate.isEmpty) {
      return null;
    }
    final hadExplicitScheme = candidate.startsWith(
      RegExp(r'https?://', caseSensitive: false),
    );
    while (candidate.isNotEmpty) {
      final last = candidate[candidate.length - 1];
      if (!_isTrailingPunctuation(last)) {
        break;
      }
      if (last == ')' && _hasMoreOpeningParens(candidate)) {
        break;
      }
      candidate = candidate.substring(0, candidate.length - 1);
    }
    if (!hadExplicitScheme && _looksLikeBareFilenameCandidate(candidate)) {
      return null;
    }
    if (!hadExplicitScheme && !_hasAllowedBareDomainSuffix(candidate)) {
      return null;
    }
    if (candidate.isNotEmpty &&
        !candidate.startsWith(RegExp(r'https?://', caseSensitive: false))) {
      candidate = 'https://$candidate';
    }
    return candidate.isEmpty ? null : candidate;
  }

  String? _canonicalPreviewKey(String url) {
    final uri = Uri.tryParse(url.trim());
    if (uri == null || uri.host.trim().isEmpty) {
      return null;
    }
    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      return null;
    }
    final host = _canonicalPreviewHost(uri.host);
    final port =
        (uri.hasPort &&
            !((scheme == 'http' && uri.port == 80) ||
                (scheme == 'https' && uri.port == 443)))
        ? ':${uri.port}'
        : '';
    var path = uri.path;
    while (path.length > 1 && path.endsWith('/')) {
      path = path.substring(0, path.length - 1);
    }
    return '$scheme://$host$port$path${uri.hasQuery ? '?${uri.query}' : ''}';
  }

  String _canonicalPreviewHost(String host) {
    final lowerHost = host.toLowerCase();
    return lowerHost.startsWith('www.')
        ? lowerHost.substring('www.'.length)
        : lowerHost;
  }

  bool _isTrailingPunctuation(String char) {
    return char == '.' ||
        char == ',' ||
        char == '!' ||
        char == '?' ||
        char == ':' ||
        char == ';' ||
        char == ')' ||
        char == ']' ||
        char == '"' ||
        char == '\'' ||
        char == '*' ||
        char == '_' ||
        char == '`' ||
        char == '。' ||
        char == '，' ||
        char == '；' ||
        char == '：' ||
        char == '！' ||
        char == '？' ||
        char == '）' ||
        char == '】' ||
        char == '》' ||
        char == '”' ||
        char == '’';
  }

  bool _hasMoreOpeningParens(String value) {
    final opening = '('.allMatches(value).length;
    final closing = ')'.allMatches(value).length;
    return closing <= opening;
  }

  bool _looksLikeImageUrl(String url) {
    final parsed = Uri.tryParse(url);
    final path = (parsed?.path ?? url).toLowerCase();
    return _hasKnownFileExtension(path, const <String>{
      'png',
      'jpg',
      'jpeg',
      'gif',
      'webp',
      'bmp',
      'svg',
      'heic',
      'heif',
    });
  }

  bool _looksLikeBareFilenameCandidate(String value) {
    final candidate = value.trim().toLowerCase();
    if (candidate.isEmpty ||
        candidate.contains('/') ||
        candidate.contains('?') ||
        candidate.contains('#')) {
      return false;
    }
    return _hasKnownFileExtension(candidate, const <String>{
      'png',
      'jpg',
      'jpeg',
      'gif',
      'webp',
      'bmp',
      'svg',
      'heic',
      'heif',
      'pdf',
      'txt',
      'md',
      'json',
      'csv',
      'doc',
      'docx',
      'xls',
      'xlsx',
      'ppt',
      'pptx',
      'zip',
      'rar',
      '7z',
      'mp3',
      'wav',
      'm4a',
      'mp4',
      'mov',
      'avi',
    });
  }

  bool _hasAllowedBareDomainSuffix(String value) {
    final parsed = Uri.tryParse('https://${value.trim()}');
    final host = _canonicalPreviewHost(parsed?.host ?? '');
    if (host.isEmpty) {
      return false;
    }
    for (final suffix in _commonBareDomainSuffixes) {
      if (host == suffix || host.endsWith('.$suffix')) {
        return true;
      }
    }
    return false;
  }

  bool _hasKnownFileExtension(String value, Set<String> extensions) {
    final pureValue = value.split('?').first.split('#').first;
    final lastDot = pureValue.lastIndexOf('.');
    if (lastDot <= 0 || lastDot == pureValue.length - 1) {
      return false;
    }
    final extension = pureValue.substring(lastDot + 1).toLowerCase();
    return extensions.contains(extension);
  }

  String _collapseWhitespace(String value) {
    return value.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  String _decodeHtmlEntities(String value) {
    return value.replaceAllMapped(RegExp(r'&(#x?[0-9a-fA-F]+|[a-zA-Z]+);'), (
      match,
    ) {
      final entity = match.group(1) ?? '';
      if (entity.isEmpty) {
        return match.group(0) ?? '';
      }
      if (entity.startsWith('#x') || entity.startsWith('#X')) {
        final codePoint = int.tryParse(entity.substring(2), radix: 16);
        return codePoint == null
            ? (match.group(0) ?? '')
            : String.fromCharCode(codePoint);
      }
      if (entity.startsWith('#')) {
        final codePoint = int.tryParse(entity.substring(1));
        return codePoint == null
            ? (match.group(0) ?? '')
            : String.fromCharCode(codePoint);
      }
      return switch (entity) {
        'amp' => '&',
        'lt' => '<',
        'gt' => '>',
        'quot' => '"',
        'apos' => '\'',
        'nbsp' => ' ',
        _ => match.group(0) ?? '',
      };
    });
  }
}
