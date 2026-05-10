/// 聊天消息下方展示的链接预览数据。
class ChatLinkPreview {
  static const String statusLoading = 'loading';
  static const String statusReady = 'ready';
  static const String statusFailed = 'failed';

  const ChatLinkPreview({
    required this.url,
    required this.domain,
    this.siteName = '',
    this.title = '',
    this.description = '',
    this.imageUrl = '',
    this.status = statusLoading,
  });

  final String url;
  final String domain;
  final String siteName;
  final String title;
  final String description;
  final String imageUrl;
  final String status;

  factory ChatLinkPreview.loading(String url) {
    return ChatLinkPreview(
      url: url,
      domain: _domainFor(url),
      status: statusLoading,
    );
  }

  factory ChatLinkPreview.failed(String url) {
    return ChatLinkPreview(
      url: url,
      domain: _domainFor(url),
      status: statusFailed,
    );
  }

  factory ChatLinkPreview.fromJson(Map<String, dynamic> json) {
    final url = (json['url'] as String? ?? '').trim();
    final domain = (json['domain'] as String? ?? '').trim();
    return ChatLinkPreview(
      url: url,
      domain: domain.isNotEmpty ? domain : _domainFor(url),
      siteName: (json['siteName'] as String? ?? '').trim(),
      title: (json['title'] as String? ?? '').trim(),
      description: (json['description'] as String? ?? '').trim(),
      imageUrl: (json['imageUrl'] as String? ?? '').trim(),
      status: _normalizeStatus((json['status'] as String? ?? '').trim()),
    );
  }

  String get displaySiteName => siteName.isNotEmpty ? siteName : domain;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'url': url,
      'domain': domain,
      'siteName': siteName,
      'title': title,
      'description': description,
      'imageUrl': imageUrl,
      'status': status,
    };
  }

  ChatLinkPreview copyWith({
    String? url,
    String? domain,
    String? siteName,
    String? title,
    String? description,
    String? imageUrl,
    String? status,
  }) {
    return ChatLinkPreview(
      url: url ?? this.url,
      domain: domain ?? this.domain,
      siteName: siteName ?? this.siteName,
      title: title ?? this.title,
      description: description ?? this.description,
      imageUrl: imageUrl ?? this.imageUrl,
      status: status ?? this.status,
    );
  }

  static String _normalizeStatus(String raw) {
    return switch (raw) {
      statusReady => statusReady,
      statusFailed => statusFailed,
      _ => statusLoading,
    };
  }

  static String _domainFor(String url) {
    final uri = Uri.tryParse(url);
    return uri?.host.trim() ?? '';
  }
}
