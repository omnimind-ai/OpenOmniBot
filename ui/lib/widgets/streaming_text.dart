import 'package:flutter/material.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

/// 思考中的加载文案（原始中文值，用于数据比较）
const String kThinkingText = '小万正在思考...';

/// 思考中的加载文案（本地化显示用）
String get kThinkingTextLocalized => AppTextLocalizer.text(kThinkingText);

/// 总结中的加载文案（本地化显示用）
String get kSummarizingText => AppTextLocalizer.text('总结中');

/// 总结完成的提示文案（本地化显示用）
String get kSummaryCompleteText => AppTextLocalizer.text('总结如下');

/// 流式文本显示组件，支持平滑渐显效果
///
/// 用于显示流式推送的文本内容，每次新增的文字都会平滑扩展并渐显
/// 支持可选的Markdown渲染功能
///
/// 示例：
/// ```dart
/// StreamingText(
///   fullText: _content,
///   style: TextStyle(fontSize: 14),
///   enableMarkdown: true, // 启用Markdown支持
/// )
/// ```
class StreamingText extends StatefulWidget {
  /// 完整的文本内容（会随着流式推送逐渐增加）
  final String fullText;

  /// 文本样式
  final TextStyle style;

  /// 是否启用Markdown渲染，默认为false
  final bool enableMarkdown;

  /// 是否可被选择
  final bool selectable;

  /// 文本流式显示发生布局变化时回调
  final VoidCallback? onDisplayedTextChanged;

  /// 尾随在文本末尾的内联组件
  final Widget? trailing;

  /// 自定义聊天内资源打开方式。
  final OmnibotResourceOpenCallback? onResourceOpen;

  /// 兼容旧流式批次元数据。
  ///
  /// Markdown 消息始终按完整 `fullText` 做块级渲染，避免把新增长文本塞进
  /// 内联占位组件后无法自然换行和撑高消息列表。
  final int? markdownRenderedLength;

  const StreamingText({
    super.key,
    required this.fullText,
    required this.style,
    this.enableMarkdown = false,
    this.selectable = false,
    this.onDisplayedTextChanged,
    this.trailing,
    this.onResourceOpen,
    this.markdownRenderedLength,
  });

  @override
  State<StreamingText> createState() => _StreamingTextState();
}

class _StreamingTextState extends State<StreamingText> {
  String _previousFullText = '';
  bool _isFirstBuild = true;
  int? _lastNotifiedDisplayLength;
  // Only incremented when animation must restart from scratch:
  // thinking→content transition, or full text replacement (not streaming append).
  int _animationEpoch = 0;

  @override
  void didUpdateWidget(StreamingText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.fullText != widget.fullText) {
      final wasThinking = oldWidget.fullText == kThinkingText;
      final isAppend = widget.fullText.startsWith(oldWidget.fullText);
      // Restart animation only on thinking→content or full replacement,
      // NOT on every streaming append.
      if (wasThinking || !isAppend) {
        _animationEpoch++;
      }
      _previousFullText = _resolveAnimationStartText(
        previousText: oldWidget.fullText,
        nextText: widget.fullText,
      );
      _lastNotifiedDisplayLength = null;
    }
  }

  String _resolveAnimationStartText({
    required String previousText,
    required String nextText,
  }) {
    if (previousText == kThinkingText) {
      return previousText;
    }
    if (nextText.startsWith(previousText)) {
      return previousText;
    }
    return nextText;
  }

  void _notifyDisplayedTextChanged(int displayLength) {
    if (_lastNotifiedDisplayLength == displayLength) {
      return;
    }
    _lastNotifiedDisplayLength = displayLength;
    final callback = widget.onDisplayedTextChanged;
    if (callback == null) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        callback();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    // 第一次build时，初始化_previousFullText
    if (_isFirstBuild) {
      _previousFullText = widget.fullText;
      _isFirstBuild = false;
    }

    // 如果是思考中文案，直接显示，不做动画
    if (widget.fullText == kThinkingText) {
      final localizedText = kThinkingTextLocalized;
      Widget child = widget.enableMarkdown
          ? OmnibotMarkdownBody(
              data: localizedText,
              baseStyle: widget.style,
              inlineResourcePlainStyle: true,
              onResourceOpen: widget.onResourceOpen,
            )
          : Text(localizedText, style: widget.style);

      return _wrapSelectable(child, localizedText);
    }

    if (widget.enableMarkdown) {
      _notifyDisplayedTextChanged(widget.fullText.length);
      return _wrapSelectable(
        _MemoizedMarkdownBody(
          text: widget.fullText,
          style: widget.style,
          onResourceOpen: widget.onResourceOpen,
          trailing: widget.trailing,
        ),
        widget.fullText,
      );
    }

    // ── 纯文本动画路径 ──
    // 如果从思考中文案切换到实际内容，从0开始
    final previousLength = _previousFullText == kThinkingText
        ? 0
        : _previousFullText.length;

    // 计算新增的字符数，用于确定动画时长
    final newCharsCount = widget.fullText.length - previousLength;

    // 根据新增字符数动态计算动画时长：字符越多，动画越快完成
    // 每个字符约15-30ms，确保流畅感
    final duration = Duration(
      milliseconds: (newCharsCount * 20).clamp(100, 800),
    );

    return TweenAnimationBuilder<double>(
      key: ValueKey(_animationEpoch), // 只在思考→内容或文本替换时重建，流式追加不重建
      tween: Tween<double>(
        begin: previousLength.toDouble(),
        end: widget.fullText.length.toDouble(),
      ),
      duration: duration,
      curve: Curves.easeOut,
      builder: (context, value, child) {
        // 计算当前应该显示的字符数
        final displayLength = _clampToCodePointBoundary(
          widget.fullText,
          value.round(),
        );
        final displayText = widget.fullText.substring(0, displayLength);
        _notifyDisplayedTextChanged(displayText.length);

        if (widget.enableMarkdown) {
          // 全量 Markdown 渲染（默认 / flush 后 / 首批文本）
          Widget child = OmnibotMarkdownBody(
            data: displayText,
            baseStyle: widget.style,
            inlineResourcePlainStyle: true,
            onResourceOpen: widget.onResourceOpen,
            trailingInline: widget.trailing,
          );

          return _wrapSelectable(child, displayText);
        }

        // 计算动画进度（0.0 到 1.0）
        final progress = newCharsCount > 0
            ? ((value - previousLength) / newCharsCount).clamp(0.0, 1.0)
            : 1.0;

        Widget child = RichText(
          text: TextSpan(
            children: _buildTextSpans(
              displayText,
              previousLength,
              progress,
              widget.trailing,
            ),
            style: widget.style,
          ),
        );

        // 计算新增部分的透明度（最后几个字符渐显）
        return _wrapSelectable(child, displayText);
      },
    );
  }

  Widget _wrapSelectable(Widget child, String copyText) {
    if (!widget.selectable) {
      return child;
    }

    // Flutter's native SelectionArea can keep detached selectables registered
    // while chat messages stream, animate, or get removed from a scrolling list.
    // Prefer stable full-message copy over granular selection for dynamic chat.
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onLongPress: () => _copyText(copyText),
      child: child,
    );
  }

  Future<void> _copyText(String text) async {
    final normalized = text.trim();
    if (normalized.isEmpty) {
      return;
    }
    final copied = await AssistsMessageService.copyToClipboard(normalized);
    if (!mounted) {
      return;
    }
    showToast(
      copied
          ? AppTextLocalizer.choose(en: 'Copied', zh: '已复制')
          : AppTextLocalizer.choose(en: 'Copy failed', zh: '复制失败'),
      type: copied ? ToastType.success : ToastType.error,
    );
  }

  /// 构建带渐变效果的文本片段
  /// [displayText] 当前要显示的文本
  /// [previousLength] 之前已显示的文本长度
  /// [progress] 动画进度 (0.0 到 1.0)
  List<InlineSpan> _buildTextSpans(
    String displayText,
    int previousLength,
    double progress,
    Widget? trailing,
  ) {
    if (displayText.length <= previousLength) {
      return _appendTrailingSpan([TextSpan(text: displayText)], trailing);
    }

    final oldText = displayText.substring(0, previousLength);
    final newText = displayText.substring(previousLength);

    // 根据进度计算透明度：从0.3逐渐到1.0
    // 使用easeIn曲线使渐入更平滑
    final opacity = 0.3 + (0.7 * progress);

    return _appendTrailingSpan([
      // 已显示的旧文本，完全不透明
      if (oldText.isNotEmpty) TextSpan(text: oldText),
      // 新增的文本，使用渐变透明度
      if (newText.isNotEmpty)
        TextSpan(
          text: newText,
          style: widget.style.copyWith(
            color: widget.style.color?.withValues(
              alpha: (widget.style.color?.a ?? 1.0) * opacity,
            ),
          ),
        ),
    ], trailing);
  }

  int _clampToCodePointBoundary(String text, int requestedLength) {
    var safeLength = requestedLength.clamp(0, text.length);
    if (safeLength <= 0 || safeLength >= text.length) {
      return safeLength;
    }
    final currentUnit = text.codeUnitAt(safeLength);
    final previousUnit = text.codeUnitAt(safeLength - 1);
    final isCurrentLowSurrogate =
        currentUnit >= 0xDC00 && currentUnit <= 0xDFFF;
    final isPreviousHighSurrogate =
        previousUnit >= 0xD800 && previousUnit <= 0xDBFF;
    if (isCurrentLowSurrogate && isPreviousHighSurrogate) {
      safeLength -= 1;
    }
    return safeLength;
  }

  List<InlineSpan> _appendTrailingSpan(
    List<InlineSpan> spans,
    Widget? trailing,
  ) {
    if (trailing == null) {
      return spans;
    }
    return [
      ...spans,
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.only(left: 4),
          child: trailing,
        ),
      ),
    ];
  }
}

/// Splits a streaming markdown response into a stable committed section and a
/// live pending section. Committed paragraphs (everything before the last
/// blank line) are rendered once and wrapped in [RepaintBoundary] so they are
/// never repainted on subsequent token arrivals. Only the last (incomplete)
/// paragraph rebuilds — O(last-paragraph) work per token instead of O(full text).
///
/// This mirrors the MemoizedMarkdownBlock pattern from the Vercel AI SDK.
class _MemoizedMarkdownBody extends StatefulWidget {
  const _MemoizedMarkdownBody({
    required this.text,
    required this.style,
    this.onResourceOpen,
    this.trailing,
  });

  final String text;
  final TextStyle style;
  final OmnibotResourceOpenCallback? onResourceOpen;
  final Widget? trailing;

  @override
  State<_MemoizedMarkdownBody> createState() => _MemoizedMarkdownBodyState();
}

final RegExp _markdownFenceLinePattern = RegExp(
  r'^[ \t]{0,3}(`{3,}|~{3,})([^\r\n]*)$',
);

class _OpenMarkdownFenceSegment {
  const _OpenMarkdownFenceSegment({
    required this.prefix,
    required this.info,
    required this.code,
  });

  final String prefix;
  final String info;
  final String code;
}

class _MemoizedMarkdownBodyState extends State<_MemoizedMarkdownBody> {
  // Text committed to the stable section — only grows, never shrinks.
  String _committedText = '';
  // Cached widget for the committed section, rebuilt only when _committedText grows.
  Widget? _committedWidget;
  String? _cachedOpenFencePrefixText;
  Widget? _cachedOpenFencePrefixWidget;

  @override
  void didUpdateWidget(_MemoizedMarkdownBody old) {
    super.didUpdateWidget(old);
    if (widget.text == old.text) return;

    if (_committedText.isNotEmpty && !widget.text.startsWith(_committedText)) {
      _committedText = '';
      _committedWidget = null;
    }

    // Commit only at markdown-safe boundaries. Blank lines inside an open code
    // fence are not safe split points because the next render would parse the
    // code tail as regular markdown.
    final breakIdx = _lastStableMarkdownBoundary(widget.text);
    if (breakIdx > _committedText.length) {
      // New paragraphs have completed — extend the committed section.
      final newCommitted = widget.text.substring(0, breakIdx);
      if (newCommitted != _committedText) {
        _committedText = newCommitted;
        _committedWidget = null; // rebuild committed on next frame
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final pending = widget.text.length > _committedText.length
        ? widget.text.substring(_committedText.length)
        : '';

    if (_committedText.isEmpty) {
      final openFence = _splitOpenMarkdownFence(widget.text);
      if (openFence != null) {
        return _buildOpenFenceBody(openFence);
      }
      // No committed section yet — render the whole text as pending.
      return OmnibotMarkdownBody(
        data: widget.text,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
        trailingInline: widget.trailing,
      );
    }

    _committedWidget ??= RepaintBoundary(
      child: OmnibotMarkdownBody(
        data: _committedText,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        _committedWidget!,
        if (pending.isNotEmpty) _buildPendingBody(pending),
      ],
    );
  }

  Widget _buildPendingBody(String pending) {
    final openFence = _splitOpenMarkdownFence(pending);
    if (openFence != null) {
      return _buildOpenFenceBody(openFence);
    }
    return OmnibotMarkdownBody(
      data: pending,
      baseStyle: widget.style,
      inlineResourcePlainStyle: true,
      onResourceOpen: widget.onResourceOpen,
      trailingInline: widget.trailing,
    );
  }

  Widget _buildOpenFenceBody(_OpenMarkdownFenceSegment segment) {
    final children = <Widget>[];
    if (segment.prefix.isNotEmpty) {
      children.add(_buildCachedOpenFencePrefix(segment.prefix));
    }
    children.add(
      _StreamingOpenCodeBlock(
        code: segment.code,
        info: segment.info,
        style: widget.style,
        trailing: widget.trailing,
      ),
    );
    if (children.length == 1) {
      return children.first;
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: children,
    );
  }

  Widget _buildCachedOpenFencePrefix(String prefix) {
    if (_cachedOpenFencePrefixText != prefix ||
        _cachedOpenFencePrefixWidget == null) {
      _cachedOpenFencePrefixText = prefix;
      _cachedOpenFencePrefixWidget = RepaintBoundary(
        child: OmnibotMarkdownBody(
          data: prefix,
          baseStyle: widget.style,
          inlineResourcePlainStyle: true,
          onResourceOpen: widget.onResourceOpen,
        ),
      );
    }
    return _cachedOpenFencePrefixWidget!;
  }

  int _lastStableMarkdownBoundary(String text) {
    var offset = 0;
    var lastBoundary = -1;
    String? fenceMarker;
    var fenceLength = 0;

    while (offset <= text.length) {
      final newlineIndex = text.indexOf('\n', offset);
      final lineEnd = newlineIndex == -1 ? text.length : newlineIndex;
      final lineBoundary = newlineIndex == -1 ? text.length : newlineIndex + 1;
      var line = text.substring(offset, lineEnd);
      if (line.endsWith('\r')) {
        line = line.substring(0, line.length - 1);
      }

      var closedFence = false;
      final match = _markdownFenceLinePattern.firstMatch(line);
      if (match != null) {
        final marker = match.group(1)!;
        final markerChar = marker[0];
        if (fenceMarker == null) {
          fenceMarker = markerChar;
          fenceLength = marker.length;
        } else if (markerChar == fenceMarker && marker.length >= fenceLength) {
          fenceMarker = null;
          fenceLength = 0;
          closedFence = true;
        }
      }

      if (closedFence) {
        lastBoundary = lineBoundary;
      } else if (fenceMarker == null && line.trim().isEmpty) {
        lastBoundary = lineBoundary;
      }

      if (newlineIndex == -1) {
        break;
      }
      offset = newlineIndex + 1;
    }

    return lastBoundary;
  }

  _OpenMarkdownFenceSegment? _splitOpenMarkdownFence(String text) {
    var offset = 0;
    String? fenceMarker;
    var fenceLength = 0;
    var openingStart = -1;
    var codeStart = -1;
    var info = '';

    while (offset <= text.length) {
      final newlineIndex = text.indexOf('\n', offset);
      final lineEnd = newlineIndex == -1 ? text.length : newlineIndex;
      var line = text.substring(offset, lineEnd);
      if (line.endsWith('\r')) {
        line = line.substring(0, line.length - 1);
      }

      final match = _markdownFenceLinePattern.firstMatch(line);
      if (match != null) {
        final marker = match.group(1)!;
        final markerChar = marker[0];
        if (fenceMarker == null) {
          fenceMarker = markerChar;
          fenceLength = marker.length;
          openingStart = offset;
          codeStart = newlineIndex == -1 ? text.length : newlineIndex + 1;
          info = (match.group(2) ?? '').trim();
        } else if (markerChar == fenceMarker && marker.length >= fenceLength) {
          fenceMarker = null;
          fenceLength = 0;
          openingStart = -1;
          codeStart = -1;
          info = '';
        }
      }

      if (newlineIndex == -1) {
        break;
      }
      offset = newlineIndex + 1;
    }

    if (fenceMarker == null || openingStart < 0 || codeStart < 0) {
      return null;
    }
    return _OpenMarkdownFenceSegment(
      prefix: text.substring(0, openingStart),
      info: info,
      code: text.substring(codeStart),
    );
  }
}

class _StreamingOpenCodeBlock extends StatelessWidget {
  const _StreamingOpenCodeBlock({
    required this.code,
    required this.info,
    required this.style,
    this.trailing,
  });

  final String code;
  final String info;
  final TextStyle style;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final codeStyle = style.copyWith(
      fontFamily: 'monospace',
      backgroundColor: Colors.transparent,
      color: theme.colorScheme.onSurfaceVariant,
      fontSize: ((style.fontSize ?? 14) * 0.92).toDouble(),
      height: 1.45,
    );
    final label = info.trim();

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (label.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    label,
                    style: style.copyWith(
                      color: theme.colorScheme.onSurfaceVariant.withValues(
                        alpha: 0.72,
                      ),
                      fontSize: ((style.fontSize ?? 14) * 0.78).toDouble(),
                      height: 1.2,
                    ),
                  ),
                ),
              ChatDrawerGestureGuard(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Text(
                    code.isEmpty ? ' ' : code,
                    style: codeStyle,
                    softWrap: false,
                  ),
                ),
              ),
              if (trailing != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: trailing,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
