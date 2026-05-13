import 'dart:convert';
import 'dart:io';
import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/app_background_service.dart';
import '../../../../../services/voice_playback_channel_service.dart';
import '../../../../../services/voice_playback_coordinator.dart';
import '../../../../../theme/theme_context.dart';
import '../../../../../widgets/streaming_text.dart';
import 'thinking_dots_indicator.dart';
import 'cards/card_widget_factory.dart';
import 'package:flutter_svg/flutter_svg.dart';

export 'package:ui/widgets/streaming_text.dart'
    show kThinkingText, kSummarizingText, kSummaryCompleteText;

const String _kSpeechIconSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M8.8 20v-4.1l1.9.2a2.3 2.3 0 0 0 2.164-2.1V8.3A5.37 5.37 0 0 0 2 8.25c0 2.8.656 3.054 1 4.55a5.77 5.77 0 0 1 .029 2.758L2 20"/>
  <path d="M19.8 17.8a7.5 7.5 0 0 0 .003-10.603"/>
  <path d="M17 15a3.5 3.5 0 0 0-.025-4.975"/>
</svg>
''';

/// 消息气泡组件
///
/// 根据消息类型渲染不同样式的气泡
/// - 用户消息：右侧深色气泡
/// - AI消息：左侧浅色气泡，使用StreamingText组件渲染流式内容
/// - 卡片消息（预留）：自定义卡片组件
class MessageBubble extends StatelessWidget {
  final ChatMessageModel message;

  /// 任务执行前的回调，用于保存聊天上下文
  final OnBeforeTaskExecute? onBeforeTaskExecute;

  /// 取消任务回调，参数为 taskId
  final void Function(String taskId)? onCancelTask;

  /// 是否允许深度思考卡片折叠
  final bool enableThinkingCollapse;

  /// 思考卡片完成后是否自动折叠
  final bool thinkingAutoCollapseOnComplete;

  /// 强制覆盖深度思考卡片的头像显示
  final bool? showThinkingAvatarOverride;

  /// 外层消息列表滚动控制器，用于卡片内嵌滚动与父列表联动
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final OnRequestAuthorize? onRequestAuthorize;
  final void Function(ChatMessageModel message, LongPressStartDetails details)?
  onUserMessageLongPressStart;
  final bool isUserMessageEditing;
  final TextEditingController? userMessageEditController;
  final VoidCallback? onCancelUserEdit;
  final VoidCallback? onSaveUserEdit;
  final VoidCallback? onStreamingTextLayoutChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  const MessageBubble({
    super.key,
    required this.message,
    this.onBeforeTaskExecute,
    this.onCancelTask,
    this.enableThinkingCollapse = false,
    this.thinkingAutoCollapseOnComplete = true,
    this.showThinkingAvatarOverride,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onUserMessageLongPressStart,
    this.isUserMessageEditing = false,
    this.userMessageEditController,
    this.onCancelUserEdit,
    this.onSaveUserEdit,
    this.onStreamingTextLayoutChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
  });

  double get _chatTextSize => appearanceConfig.chatTextSize;
  double get _chatTextScale => resolvedChatTextScale(appearanceConfig);

  bool _usesThemeDrivenText() {
    return !appearanceConfig.isActive &&
        appearanceConfig.chatTextColorMode != AppBackgroundTextColorMode.custom;
  }

  Color _resolvedAiPrimaryTextColor(BuildContext context) {
    return _usesThemeDrivenText()
        ? context.omniPalette.textPrimary
        : visualProfile.primaryTextColor;
  }

  Color _resolvedAiSecondaryTextColor(BuildContext context) {
    return _usesThemeDrivenText()
        ? context.omniPalette.textSecondary
        : visualProfile.secondaryTextColor;
  }

  @override
  Widget build(BuildContext context) {
    // user: 1=用户, 2=AI, 3=系统
    final isUserMessage = message.user == 1;
    final isCardMessage = message.type == 2; // 卡片消息

    // 卡片消息特殊布局：撑满整个聊天框宽度
    if (isCardMessage) {
      return Container(
        margin: const EdgeInsets.only(top: 8, bottom: 0),
        child: _buildMessageContent(context, isUserMessage),
      );
    }

    return Container(
      margin: EdgeInsets.only(
        top: isUserMessage ? 24 : 8,
        bottom: isUserMessage ? 16 : 0,
        right: isUserMessage ? 0 : 18.34,
      ),
      child: Row(
        mainAxisAlignment: isUserMessage
            ? MainAxisAlignment.end
            : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          isUserMessage
              ? Flexible(child: _buildMessageContent(context, isUserMessage))
              : Expanded(child: _buildMessageContent(context, isUserMessage)),
        ],
      ),
    );
  }

  /// 构建消息内容
  Widget _buildMessageContent(BuildContext context, bool isUserMessage) {
    // 根据消息类型渲染不同内容
    // type: 1=普通消息, 2=卡片消息
    switch (message.type) {
      case 1: // 普通消息
        return _buildTextMessage(context, isUserMessage);

      case 2: // 卡片消息
        return _buildCardMessage(context);

      default:
        return _buildTextMessage(context, isUserMessage);
    }
  }

  /// 构建文本消息
  Widget _buildTextMessage(BuildContext context, bool isUserMessage) {
    final text = message.text ?? '';
    final attachments = _extractAttachments();
    final linkPreviews = message.linkPreviews;

    if (isUserMessage) {
      // 用户消息：整块气泡长按触发快捷操作。
      return LayoutBuilder(
        builder: (context, constraints) {
          final fallbackMaxWidth = MediaQuery.of(context).size.width * 0.75;
          final availableWidth = constraints.maxWidth.isFinite
              ? constraints.maxWidth
              : fallbackMaxWidth;
          final maxBubbleWidth = availableWidth * 0.78;
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onLongPressStart:
                isUserMessageEditing || onUserMessageLongPressStart == null
                ? null
                : (details) => onUserMessageLongPressStart!(message, details),
            child: Container(
              key: ValueKey('user-message-bubble-${message.id}'),
              constraints: BoxConstraints(maxWidth: maxBubbleWidth),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              decoration: ShapeDecoration(
                color: visualProfile.userBubbleColor,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (isUserMessageEditing && userMessageEditController != null)
                    _buildUserEditComposer(
                      context,
                      userMessageEditController!,
                      attachments,
                    )
                  else ...[
                    if (text.isNotEmpty) _buildUserText(text),
                    if (attachments.isNotEmpty) ...[
                      if (text.isNotEmpty) const SizedBox(height: 8),
                      _buildUserAttachmentList(context, attachments),
                    ],
                  ],
                  if (!isUserMessageEditing && linkPreviews.isNotEmpty) ...[
                    if (text.isNotEmpty || attachments.isNotEmpty)
                      const SizedBox(height: 8),
                    _buildLinkPreviewList(
                      context,
                      linkPreviews,
                      compactStyle: true,
                      isUserMessage: true,
                    ),
                  ],
                ],
              ),
            ),
          );
        },
      );
    }

    if (attachments.isEmpty && linkPreviews.isEmpty) {
      // AI消息：简单文本样式，无背景
      return _buildAiTextWithSpeed(context, text);
    }

    // AI 消息按“正文 -> 附件 -> 链接预览”顺序分块展示。
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (text.isNotEmpty) _buildAiTextWithSpeed(context, text),
        if (attachments.isNotEmpty) ...[
          if (text.isNotEmpty) const SizedBox(height: 8),
          _buildUserAttachmentList(context, attachments),
        ],
        if (linkPreviews.isNotEmpty) ...[
          if (text.isNotEmpty || attachments.isNotEmpty)
            const SizedBox(height: 8),
          _buildLinkPreviewList(
            context,
            linkPreviews,
            compactStyle: true,
            isUserMessage: false,
          ),
        ],
      ],
    );
  }

  List<Map<String, dynamic>> _extractAttachments() {
    final raw = message.content?['attachments'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  Widget _buildUserAttachmentList(
    BuildContext context,
    List<Map<String, dynamic>> attachments,
  ) {
    // Collect all image sources for multi-image preview
    final imageAttachments = attachments.where(_isImageAttachment).toList();
    final imageSources = imageAttachments
        .map(_resolveImageSource)
        .whereType<ImagePreviewSource>()
        .toList();
    final heroTags = List.generate(
      imageSources.length,
      (i) => 'img_preview_${message.id}_$i',
    );

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: attachments.map((item) {
        if (_isImageAttachment(item)) {
          final imageIndex = imageAttachments.indexOf(item);
          return _buildImageAttachmentTile(
            context,
            item,
            imageSources,
            imageIndex,
            heroTags,
          );
        }
        return _buildFileAttachmentChip(item);
      }).toList(),
    );
  }

  Widget _buildImageAttachmentTile(
    BuildContext context,
    Map<String, dynamic> item,
    List<ImagePreviewSource> allSources,
    int tappedIndex,
    List<String> heroTags,
  ) {
    final heroTag = heroTags[tappedIndex];
    return GestureDetector(
      onTap: () {
        if (allSources.isNotEmpty) {
          ImagePreviewOverlay.showAll(
            context,
            sources: allSources,
            initialIndex: tappedIndex.clamp(0, allSources.length - 1),
            heroTags: heroTags,
          );
        }
      },
      child: Container(
        width: 84,
        height: 84,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: visualProfile.attachmentSurfaceColor,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: visualProfile.attachmentBorderColor,
            width: 1,
          ),
        ),
        child: Hero(tag: heroTag, child: _buildAttachmentImageWidget(item)),
      ),
    );
  }

  ImagePreviewSource? _resolveImageSource(Map<String, dynamic> item) {
    final dataUrl = (item['dataUrl'] as String? ?? '').trim();
    if (dataUrl.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(dataUrl);
      if (bytes != null) return MemoryImageSource(bytes);
    }
    final url = (item['url'] as String? ?? '').trim();
    if (url.startsWith('http://') || url.startsWith('https://')) {
      return NetworkImageSource(url);
    }
    if (url.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(url);
      if (bytes != null) return MemoryImageSource(bytes);
    }
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty && !path.startsWith('http')) {
      return FileImageSource(path);
    }
    return null;
  }

  Widget _buildAttachmentImageWidget(Map<String, dynamic> item) {
    final dataUrl = (item['dataUrl'] as String? ?? '').trim();
    if (dataUrl.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(dataUrl);
      if (bytes != null) {
        return Image.memory(
          bytes,
          fit: BoxFit.cover,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => _buildImageFallback(),
        );
      }
    }

    final url = (item['url'] as String? ?? '').trim();
    if (url.startsWith('http://') || url.startsWith('https://')) {
      return Image.network(
        url,
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _buildImageFallback(),
      );
    }
    if (url.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(url);
      if (bytes != null) {
        return Image.memory(
          bytes,
          fit: BoxFit.cover,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => _buildImageFallback(),
        );
      }
    }

    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty && !path.startsWith('http')) {
      return Image.file(
        File(path),
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _buildImageFallback(),
      );
    }
    return _buildImageFallback();
  }

  Widget _buildImageFallback() {
    return Center(
      child: Icon(
        Icons.image_not_supported_outlined,
        size: 20,
        color: visualProfile.attachmentIconColor,
      ),
    );
  }

  Widget _buildFileAttachmentChip(Map<String, dynamic> item) {
    final displayName = _resolveAttachmentDisplayName(item);
    final sizeText = _formatAttachmentSize(item['size']);

    return Container(
      constraints: const BoxConstraints(maxWidth: 220, minHeight: 40),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: visualProfile.attachmentSurfaceColor,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: visualProfile.attachmentBorderColor,
          width: 1,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.insert_drive_file_outlined,
            size: 15,
            color: visualProfile.attachmentIconColor,
          ),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              sizeText.isEmpty ? displayName : '$displayName\n$sizeText',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: visualProfile.attachmentTextColor,
                fontSize: 12,
                fontWeight: FontWeight.w500,
                height: 1.25,
              ),
            ),
          ),
        ],
      ),
    );
  }

  // 链接预览独立成块，便于保持引用式层级和轻量点击反馈。
  Widget _buildLinkPreviewList(
    BuildContext context,
    List<ChatLinkPreview> previews, {
    required bool compactStyle,
    required bool isUserMessage,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: previews.asMap().entries.map((entry) {
        return Padding(
          padding: EdgeInsets.only(
            bottom: entry.key == previews.length - 1 ? 0 : 8,
          ),
          child: _buildLinkPreviewCard(
            context,
            entry.value,
            entry.key,
            compactStyle: compactStyle,
            isUserMessage: isUserMessage,
          ),
        );
      }).toList(),
    );
  }

  Widget _buildLinkPreviewCard(
    BuildContext context,
    ChatLinkPreview preview,
    int index, {
    required bool compactStyle,
    required bool isUserMessage,
  }) {
    if (compactStyle) {
      return _buildCompactLinkPreview(
        context,
        preview,
        index,
        isUserMessage: isUserMessage,
      );
    }
    final title = preview.title.trim();
    final description = preview.description.trim();
    final siteName = preview.displaySiteName.trim();
    final hasImage =
        preview.imageUrl.startsWith('http://') ||
        preview.imageUrl.startsWith('https://');
    final statusLabel = switch (preview.status) {
      ChatLinkPreview.statusLoading => AppTextLocalizer.choose(
        zh: '加载预览中',
        en: 'Loading preview',
        locale: Localizations.localeOf(context),
      ),
      ChatLinkPreview.statusFailed => AppTextLocalizer.choose(
        zh: '预览暂不可用',
        en: 'Preview unavailable',
        locale: Localizations.localeOf(context),
      ),
      _ => '',
    };

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: ValueKey('link-preview-card-$index'),
        onTap: () {
          if (preview.url.isEmpty) {
            return;
          }
          _handleChatLinkTap(context, preview.url);
        },
        borderRadius: BorderRadius.circular(14),
        child: Container(
          constraints: const BoxConstraints(maxWidth: 360),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: visualProfile.attachmentSurfaceColor,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: visualProfile.attachmentBorderColor,
              width: 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (siteName.isNotEmpty)
                      Text(
                        siteName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: visualProfile.secondaryTextColor,
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    if (title.isNotEmpty) ...[
                      if (siteName.isNotEmpty) const SizedBox(height: 4),
                      Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: visualProfile.primaryTextColor,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          height: 1.25,
                        ),
                      ),
                    ],
                    if (description.isNotEmpty) ...[
                      if (title.isNotEmpty || siteName.isNotEmpty)
                        const SizedBox(height: 4),
                      Text(
                        description,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: visualProfile.secondaryTextColor,
                          fontSize: 12,
                          height: 1.35,
                        ),
                      ),
                    ],
                    if (statusLabel.isNotEmpty &&
                        preview.status != ChatLinkPreview.statusReady) ...[
                      if (title.isNotEmpty ||
                          description.isNotEmpty ||
                          siteName.isNotEmpty)
                        const SizedBox(height: 4),
                      Text(
                        statusLabel,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: visualProfile.secondaryTextColor,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (hasImage) ...[
                const SizedBox(width: 12),
                ClipRRect(
                  borderRadius: BorderRadius.circular(10),
                  child: Image.network(
                    preview.imageUrl,
                    width: 72,
                    height: 72,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) =>
                        _buildLinkPreviewImageFallback(),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCompactLinkPreview(
    BuildContext context,
    ChatLinkPreview preview,
    int index, {
    required bool isUserMessage,
  }) {
    final title = preview.title.trim();
    final description = preview.description.trim();
    final siteName = preview.displaySiteName.trim();
    final hasImage =
        preview.imageUrl.startsWith('http://') ||
        preview.imageUrl.startsWith('https://');
    final primaryColor = isUserMessage
        ? visualProfile.primaryTextColor
        : _resolvedAiPrimaryTextColor(context);
    final secondaryBaseColor = isUserMessage
        ? visualProfile.primaryTextColor
        : _resolvedAiSecondaryTextColor(context);
    final lineColor = secondaryBaseColor.withValues(alpha: 0.42);
    final metaColor = secondaryBaseColor.withValues(alpha: 0.82);
    final secondaryColor = secondaryBaseColor.withValues(alpha: 0.94);
    final statusLabel = switch (preview.status) {
      ChatLinkPreview.statusLoading => AppTextLocalizer.choose(
        zh: '加载预览中',
        en: 'Loading preview',
        locale: Localizations.localeOf(context),
      ),
      ChatLinkPreview.statusFailed => AppTextLocalizer.choose(
        zh: '预览暂不可用',
        en: 'Preview unavailable',
        locale: Localizations.localeOf(context),
      ),
      _ => '',
    };

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: ValueKey('link-preview-card-$index'),
        onTap: () {
          if (preview.url.isEmpty) {
            return;
          }
          _handleChatLinkTap(context, preview.url);
        },
        borderRadius: BorderRadius.circular(8),
        child: Container(
          key: ValueKey('link-preview-quote-$index'),
          constraints: BoxConstraints(maxWidth: isUserMessage ? 320 : 360),
          padding: const EdgeInsets.only(left: 10, top: 2, right: 2, bottom: 2),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: lineColor, width: 3)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (siteName.isNotEmpty)
                        Text(
                          siteName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: metaColor,
                            fontSize: 10,
                            fontWeight: FontWeight.w500,
                            height: 1.2,
                          ),
                        ),
                      if (title.isNotEmpty) ...[
                        if (siteName.isNotEmpty) const SizedBox(height: 2),
                        Text(
                          title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: primaryColor,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            height: 1.22,
                          ),
                        ),
                      ],
                      if (description.isNotEmpty) ...[
                        if (title.isNotEmpty || siteName.isNotEmpty)
                          const SizedBox(height: 2),
                        Text(
                          description,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: secondaryColor,
                            fontSize: 10.5,
                            height: 1.25,
                          ),
                        ),
                      ],
                      if (statusLabel.isNotEmpty &&
                          preview.status != ChatLinkPreview.statusReady) ...[
                        if (title.isNotEmpty ||
                            description.isNotEmpty ||
                            siteName.isNotEmpty)
                          const SizedBox(height: 2),
                        Text(
                          statusLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: metaColor,
                            fontSize: 10,
                            height: 1.2,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              if (hasImage) ...[
                const SizedBox(width: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: Image.network(
                    preview.imageUrl,
                    width: 40,
                    height: 40,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) =>
                        _buildCompactLinkPreviewImageFallback(),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCompactLinkPreviewImageFallback() {
    return Container(
      width: 40,
      height: 40,
      alignment: Alignment.center,
      color: visualProfile.primaryTextColor.withValues(alpha: 0.08),
      child: Icon(
        Icons.link_outlined,
        size: 14,
        color: visualProfile.primaryTextColor.withValues(alpha: 0.72),
      ),
    );
  }

  Future<void> _handleChatLinkTap(BuildContext context, String url) async {
    if (url.startsWith('omnibot://')) {
      await OmnibotResourceService.ensureWorkspacePathsLoaded();
      if (!context.mounted) return;
      final metadata = OmnibotResourceService.resolveUri(url);
      if (metadata != null) {
        await showOmnibotArtifactPreviewSheet(context, metadata);
        return;
      }
    }
    await OmnibotResourceService.handleLinkTap(url);
  }

  Widget _buildLinkPreviewImageFallback() {
    return Container(
      width: 72,
      height: 72,
      color: visualProfile.attachmentSurfaceColor,
      alignment: Alignment.center,
      child: Icon(
        Icons.link_outlined,
        size: 18,
        color: visualProfile.attachmentIconColor,
      ),
    );
  }

  bool _isImageAttachment(Map<String, dynamic> item) {
    final explicit = item['isImage'];
    if (explicit is bool && explicit) return true;
    final mimeType = (item['mimeType'] as String? ?? '').trim().toLowerCase();
    if (mimeType.startsWith('image/')) return true;
    final path = (item['path'] as String? ?? '').toLowerCase();
    final url = (item['url'] as String? ?? '').toLowerCase();
    final dataUrl = (item['dataUrl'] as String? ?? '').toLowerCase();
    return _looksLikeImage(path) ||
        _looksLikeImage(url) ||
        dataUrl.startsWith('data:image/');
  }

  bool _looksLikeImage(String value) {
    if (value.isEmpty) return false;
    final pure = value.split('?').first;
    return pure.endsWith('.png') ||
        pure.endsWith('.jpg') ||
        pure.endsWith('.jpeg') ||
        pure.endsWith('.gif') ||
        pure.endsWith('.webp') ||
        pure.endsWith('.bmp') ||
        pure.endsWith('.heic') ||
        pure.endsWith('.heif');
  }

  String _resolveAttachmentDisplayName(Map<String, dynamic> item) {
    final name = (item['name'] as String? ?? '').trim();
    if (name.isNotEmpty) return name;
    final fileName = (item['fileName'] as String? ?? '').trim();
    if (fileName.isNotEmpty) return fileName;
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty) {
      final normalizedPath = path.replaceAll('\\', '/');
      return normalizedPath.split('/').last;
    }
    final url = (item['url'] as String? ?? '').trim();
    if (url.isNotEmpty) {
      final normalizedUrl = url.split('?').first;
      final segments = normalizedUrl.split('/');
      if (segments.isNotEmpty && segments.last.isNotEmpty) {
        return segments.last;
      }
    }
    return '附件';
  }

  /// Cache decoded data-URL bytes so that repeated [build] calls reuse
  /// the same [Uint8List] instance.  This prevents [Image.memory] from
  /// treating each rebuild as a brand-new image (cache-miss → flicker).
  static final Map<int, Uint8List> _dataUrlBytesCache = {};
  static const int _maxCacheEntries = 200;

  static Uint8List? _decodeDataUrlBytes(String value) {
    final key = value.hashCode;
    final cached = _dataUrlBytesCache[key];
    if (cached != null) return cached;

    final comma = value.indexOf(',');
    if (comma < 0 || comma + 1 >= value.length) return null;
    final base64Part = value.substring(comma + 1);
    try {
      final bytes = base64Decode(base64Part);
      if (_dataUrlBytesCache.length >= _maxCacheEntries) {
        _dataUrlBytesCache.remove(_dataUrlBytesCache.keys.first);
      }
      _dataUrlBytesCache[key] = bytes;
      return bytes;
    } catch (_) {
      return null;
    }
  }

  String _formatAttachmentSize(dynamic rawSize) {
    final int? size = rawSize is int
        ? rawSize
        : (rawSize is String ? int.tryParse(rawSize) : null);
    if (size == null || size <= 0) return '';
    if (size < 1024) return '${size}B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)}KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  Widget _buildUserEditComposer(
    BuildContext context,
    TextEditingController controller,
    List<Map<String, dynamic>> attachments,
  ) {
    return ValueListenableBuilder<TextEditingValue>(
      valueListenable: controller,
      builder: (context, value, _) {
        final hasText = value.text.trim().isNotEmpty;
        final canSave = hasText || attachments.isNotEmpty;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: controller,
              minLines: 1,
              maxLines: 6,
              autofocus: true,
              style: TextStyle(
                color: visualProfile.primaryTextColor,
                fontSize: _chatTextSize,
                fontFamily: 'PingFang SC',
                fontWeight: FontWeight.w400,
                height: 1.43,
                letterSpacing: 0.33,
              ),
              decoration: InputDecoration(
                filled: false,
                fillColor: Colors.transparent,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                disabledBorder: InputBorder.none,
                errorBorder: InputBorder.none,
                focusedErrorBorder: InputBorder.none,
                isDense: true,
                contentPadding: EdgeInsets.zero,
                hintText: context.trText('编辑你的消息'),
                hintStyle: TextStyle(
                  color: visualProfile.primaryTextColor.withValues(alpha: 0.6),
                  fontSize: _chatTextSize,
                ),
              ),
            ),
            if (attachments.isNotEmpty) ...[
              const SizedBox(height: 8),
              _buildUserAttachmentList(context, attachments),
            ],
            const SizedBox(height: 8),
            OverflowBar(
              alignment: MainAxisAlignment.end,
              spacing: 8,
              overflowAlignment: OverflowBarAlignment.end,
              children: [
                TextButton(
                  onPressed: onCancelUserEdit,
                  child: Text(context.trText('取消'), softWrap: false),
                ),
                FilledButton(
                  onPressed: canSave ? onSaveUserEdit : null,
                  style: FilledButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                  ),
                  child: Text(context.trText('保存并发送'), softWrap: false),
                ),
              ],
            ),
          ],
        );
      },
    );
  }

  /// 构建用户文本（不使用流式效果）
  Widget _buildUserText(String text) {
    return Text(
      text,
      style: TextStyle(
        color: visualProfile.primaryTextColor,
        fontSize: _chatTextSize,
        fontFamily: 'PingFang SC',
        fontWeight: FontWeight.w400,
        height: 1.43,
        letterSpacing: 0.33,
      ),
      textAlign: TextAlign.left,
    );
  }

  /// 构建AI文本（使用StreamingText组件）
  Widget _buildAiText(BuildContext context, String text, {Widget? trailing}) {
    final aiPrimaryTextColor = _resolvedAiPrimaryTextColor(context);
    final aiSecondaryTextColor = _resolvedAiSecondaryTextColor(context);
    final enableMarkdown = _shouldRenderMarkdown();
    // 如果是 loading 状态，显示浮动三个点动画（左对齐，与回复文本位置一致）
    if (message.isLoading) {
      return Align(
        alignment: Alignment.centerLeft,
        child: ThinkingDotsIndicator(dotColor: aiSecondaryTextColor),
      );
    }

    // 如果是总结中状态，显示"总结中"提示
    if (message.isSummarizing) {
      return _buildSummarizingIndicator();
    }

    // 如果有内容且之前是总结状态（通过taskId判断），显示"总结如下"前缀
    final bool isSummaryContent =
        message.id.startsWith('vlm-summary-') ||
        message.id.startsWith('task-summary-');

    if (isSummaryContent && text.isNotEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSummaryCompleteHeader(),
          const SizedBox(height: 8),
          StreamingText(
            enableMarkdown: enableMarkdown,
            markdownRenderedLength: _markdownRenderedLength(),
            fullText: text,
            selectable: true,
            onDisplayedTextChanged: onStreamingTextLayoutChanged,
            onResourceOpen: showOmnibotArtifactPreviewSheet,
            trailing: trailing,
            style: TextStyle(
              fontSize: _chatTextSize,
              color: aiPrimaryTextColor,
              height: 1.57,
            ),
          ),
        ],
      );
    }

    return StreamingText(
      enableMarkdown: enableMarkdown,
      markdownRenderedLength: _markdownRenderedLength(),
      fullText: text,
      selectable: true,
      onDisplayedTextChanged: onStreamingTextLayoutChanged,
      onResourceOpen: showOmnibotArtifactPreviewSheet,
      trailing: trailing,
      style: TextStyle(
        fontSize: _chatTextSize,
        color: aiPrimaryTextColor,
        height: 1.57,
      ),
    );
  }

  bool _shouldRenderMarkdown() {
    final raw = message.content?['renderMarkdown'];
    return raw is bool ? raw : true;
  }

  int? _markdownRenderedLength() {
    final raw = message.content?['markdownRenderedLength'];
    return raw is int ? raw : null;
  }

  /// AI text with optional inference speed label
  Widget _buildAiTextWithSpeed(BuildContext context, String text) {
    final speed = _decodeTokensPerSecond;
    final showVoiceButton = VoicePlaybackCoordinator.instance
        .shouldShowVoiceButton(
          user: message.user,
          type: message.type,
          text: text,
        );
    final aiText = _buildAiText(
      context,
      text,
      trailing: showVoiceButton ? _buildVoiceAction(context, text) : null,
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        aiText,
        if (speed != null) ...[
          const SizedBox(height: 4),
          Text(
            '${speed.toStringAsFixed(1)} tok/s',
            style: TextStyle(
              fontSize: 11,
              color: visualProfile.secondaryTextColor.withValues(alpha: 0.6),
              fontWeight: FontWeight.w400,
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildVoiceAction(BuildContext context, String text) {
    unawaited(VoicePlaybackCoordinator.instance.ensureInitialized());
    return AnimatedBuilder(
      animation: VoicePlaybackCoordinator.instance,
      builder: (context, _) {
        final playbackState = VoicePlaybackCoordinator.instance.stateFor(
          message.id,
        );
        final iconColor = _resolvedAiSecondaryTextColor(context);
        final status = playbackState.status;
        final Widget icon = switch (status) {
          VoicePlaybackStatus.synthesizing => SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(strokeWidth: 2, color: iconColor),
          ),
          VoicePlaybackStatus.playing => Icon(
            Icons.pause_rounded,
            size: 20,
            color: iconColor,
          ),
          VoicePlaybackStatus.paused => Icon(
            Icons.play_arrow_rounded,
            size: 20,
            color: iconColor,
          ),
          _ => SvgPicture.string(
            _kSpeechIconSvg,
            width: 18,
            height: 18,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          ),
        };
        final Future<void> Function() action = switch (status) {
          VoicePlaybackStatus.synthesizing =>
            () => VoicePlaybackCoordinator.instance.stopPlayback(message.id),
          _ => () => VoicePlaybackCoordinator.instance.togglePlayback(
            messageId: message.id,
            text: text,
          ),
        };
        return IconButton(
          visualDensity: VisualDensity.compact,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints.tightFor(width: 22, height: 22),
          splashRadius: 12,
          tooltip: playbackState.error.isNotEmpty
              ? playbackState.error
              : (status == VoicePlaybackStatus.playing ? '暂停语音' : '播放语音'),
          onPressed: () {
            unawaited(action());
          },
          icon: icon,
        );
      },
    );
  }

  double? get _decodeTokensPerSecond {
    final raw = message.content?['decodeTokensPerSecond'];
    if (raw is double) return raw;
    if (raw is num) return raw.toDouble();
    if (raw is String) return double.tryParse(raw);
    return null;
  }

  /// 构建"总结中"指示器
  Widget _buildSummarizingIndicator() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SvgPicture.asset(
          'assets/common/summary_loading.svg',
          width: 16,
          height: 16,
          colorFilter: ColorFilter.mode(
            visualProfile.accentBlue,
            BlendMode.srcIn,
          ),
        ),
        const SizedBox(width: 6),
        Text(
          kSummarizingText,
          style: TextStyle(
            fontSize: 14 * _chatTextScale,
            color: visualProfile.accentBlue,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(width: 8),
        ThinkingDotsIndicator(
          dotColor: visualProfile.accentBlue,
          dotSize: 6.0,
          spacing: 3.0,
        ),
      ],
    );
  }

  /// 构建"总结如下"标题
  Widget _buildSummaryCompleteHeader() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SvgPicture.asset(
          'assets/common/summary_complete.svg',
          width: 16,
          height: 16,
          colorFilter: ColorFilter.mode(
            visualProfile.accentGreen,
            BlendMode.srcIn,
          ),
        ),
        const SizedBox(width: 6),
        Text(
          kSummaryCompleteText,
          style: TextStyle(
            fontSize: 14 * _chatTextScale,
            color: visualProfile.accentGreen,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }

  /// 构建卡片消息
  Widget _buildCardMessage(BuildContext context) {
    final cardData = Map<String, dynamic>.from(message.cardData ?? {});
    cardData.putIfAbsent('cardId', () => message.contentId ?? message.id);

    // 卡片消息撑满聊天框宽度，减去头像和间距的宽度
    return SizedBox(
      width: double.infinity,
      child: CardWidgetFactory.createCard(
        cardData,
        onBeforeTaskExecute: onBeforeTaskExecute,
        onRequestAuthorize: onRequestAuthorize,
        onCancelTask: onCancelTask,
        enableThinkingCollapse: enableThinkingCollapse,
        thinkingAutoCollapseOnComplete: thinkingAutoCollapseOnComplete,
        showThinkingAvatarOverride: showThinkingAvatarOverride,
        parentScrollController: parentScrollController,
        onParentScrollHandoff: onParentScrollHandoff,
        onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
        appearanceConfig: appearanceConfig,
        visualProfile: visualProfile,
      ),
    );
  }
}
