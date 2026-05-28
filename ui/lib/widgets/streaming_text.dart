import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

/// 思考中的加载文案（原始中文值，用于数据比较）
const String kThinkingText = '小万正在思考...';

/// 思考中的加载文案（本地化显示用）
String get kThinkingTextLocalized =>
    LegacyTextLocalizer.localize(kThinkingText);

/// 总结中的加载文案（本地化显示用）
String get kSummarizingText => LegacyTextLocalizer.localize('总结中');

/// 总结完成的提示文案（本地化显示用）
String get kSummaryCompleteText => LegacyTextLocalizer.localize('总结如下');

/// 流式文本显示组件，支持平滑渐显效果
///
/// 用于显示流式推送的文本内容
///
/// **性能策略**：
/// - 启用 Markdown 时，绝不在动画帧里重新解析 markdown。
///   - 已有 [markdownRenderedLength]（>0 且 <fullText）时：走 fast-path，
///     固化 markdown 前缀 + 纯文本尾部，仅对尾部做 Opacity 渐入。
///   - 否则：一次性渲染最新 markdown，不做逐字动画。
/// - 未启用 Markdown 时仍走 TweenAnimationBuilder 做逐字渐显（轻量）。
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

  /// 已完成 Markdown 渲染的文本长度（字符数）。
  ///
  /// 当流式输出时，每 N 个 chunk 才执行一次 Markdown 渲染。该值表示上次
  /// flush 时已渲染为 Markdown 的文本长度。超出该长度的新文本以纯文本追加，
  /// 避免整段文本在 Markdown 与纯文本之间来回跳动。
  ///
  /// - `null`：整段文本按 Markdown 渲染（默认行为 / flush 后）
  /// - `0`：尚未执行过 flush，全部按 Markdown 渲染（避免首批文本跳变）
  /// - `> 0 && < fullText.length`：前缀按 Markdown 渲染，尾部按纯文本追加
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
  String? _lastSelectedContent; // 跟踪最后选中的内容
  int? _lastNotifiedDisplayLength;

  // ── Markdown 前缀缓存 ──
  // 当 mdText 不变时，复用同一 OmnibotMarkdownBody widget 对象，
  // Flutter 的 identical() 检查会跳过整棵子树的更新（含 markdown 解析）。
  // 变化的 trailing（纯文本尾部）通过 ValueNotifier 独立更新。
  String? _cachedMdPrefixText;
  TextStyle? _cachedMdPrefixStyle;
  OmnibotResourceOpenCallback? _cachedMdPrefixOnResourceOpen;
  Widget? _cachedMdPrefixWidget;
  final ValueNotifier<Widget?> _trailingInlineNotifier = ValueNotifier(null);

  @override
  void dispose() {
    _trailingInlineNotifier.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(StreamingText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.fullText != widget.fullText) {
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

      return _wrapSelectable(child);
    }

    if (widget.enableMarkdown) {
      return _buildMarkdownContent();
    }

    return _buildPlainAnimatedContent();
  }

  // ── Markdown 路径 ──
  // 不在 TweenAnimationBuilder 里重渲染 markdown（避免 O(N²) 重解析）。
  // 优先走 fast-path：cached markdown 前缀 + 尾部 Opacity 动画。
  // 回退路径：直接渲染最新 fullText，不做动画。
  Widget _buildMarkdownContent() {
    final mdLen = widget.markdownRenderedLength;
    if (mdLen != null && mdLen > 0 && mdLen < widget.fullText.length) {
      return _buildMarkdownFastPath(mdLen);
    }
    _notifyDisplayedTextChanged(widget.fullText.length);
    return _wrapSelectable(
      OmnibotMarkdownBody(
        data: widget.fullText,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
        trailingInline: widget.trailing,
      ),
    );
  }

  Widget _buildMarkdownFastPath(int mdLen) {
    final safeMdLen = _clampToCodePointBoundary(widget.fullText, mdLen);
    final mdText = widget.fullText.substring(0, safeMdLen);
    final plainTail = widget.fullText.substring(safeMdLen);

    _notifyDisplayedTextChanged(widget.fullText.length);

    // 尾部走带 Opacity 渐入的 stateful widget（轻量重绘，不触碰 markdown 子树）。
    final inlineTrailing = (plainTail.isEmpty && widget.trailing == null)
        ? null
        : _AnimatedStreamingTail(
            key: const ValueKey('omnibot-streaming-tail'),
            text: plainTail,
            style: widget.style,
            trailing: widget.trailing,
          );

    // 缓存命中：mdText 与 style/回调均未变化 → 复用 identical widget，
    // 仅通过 ValueNotifier 推送新的 trailing。
    if (_cachedMdPrefixText == mdText &&
        _cachedMdPrefixStyle == widget.style &&
        identical(_cachedMdPrefixOnResourceOpen, widget.onResourceOpen) &&
        _cachedMdPrefixWidget != null) {
      _trailingInlineNotifier.value = inlineTrailing;
    } else {
      _cachedMdPrefixText = mdText;
      _cachedMdPrefixStyle = widget.style;
      _cachedMdPrefixOnResourceOpen = widget.onResourceOpen;
      _trailingInlineNotifier.value = inlineTrailing;
      _cachedMdPrefixWidget = OmnibotMarkdownBody(
        data: mdText,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
        trailingInline: ValueListenableBuilder<Widget?>(
          valueListenable: _trailingInlineNotifier,
          builder: (_, child, __) => child ?? const SizedBox.shrink(),
        ),
      );
    }

    return _wrapSelectable(_cachedMdPrefixWidget!);
  }

  // ── 纯文本路径 ──
  // 仍保留逐字渐显动画。RichText 重建廉价，可承受 60fps。
  Widget _buildPlainAnimatedContent() {
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
      key: ValueKey(previousLength), // 确保从"思考中..."切换时重建动画
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

        return _wrapSelectable(child);
      },
    );
  }

  Widget _wrapSelectable(Widget child) {
    if (!widget.selectable) {
      return child;
    }
    return SelectionArea(
      onSelectionChanged: (content) {
        _lastSelectedContent = content?.plainText;
      },
      contextMenuBuilder: (context, selectableRegionState) {
        return _buildSelectionContextMenu(selectableRegionState);
      },
      child: child,
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

  /// 构建选择文本的上下文菜单（使用 AssistsMessageService 复制到剪贴板）
  Widget _buildSelectionContextMenu(
    SelectableRegionState selectableRegionState,
  ) {
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: selectableRegionState.contextMenuAnchors,
      buttonItems: [
        // 全选按钮
        ContextMenuButtonItem(
          label: LegacyTextLocalizer.localize('全选'),
          onPressed: () {
            selectableRegionState.selectAll(SelectionChangedCause.toolbar);
          },
        ),
        // 复制按钮 - 使用 native channel 复制
        ContextMenuButtonItem(
          label: LegacyTextLocalizer.localize('复制'),
          onPressed: () {
            // 使用 onSelectionChanged 回调跟踪到的选中内容
            final selectedText = _lastSelectedContent;

            if (selectedText != null && selectedText.isNotEmpty) {
              // 使用 native channel 复制到剪贴板
              AssistsMessageService.copyToClipboard(selectedText);
            }

            selectableRegionState.hideToolbar();
          },
        ),
      ],
    );
  }
}

/// 流式尾部文本（fast-path 内嵌）。
///
/// 当 [text] 增长时，对新增片段做 Opacity 0.3 → 1.0 的渐入动画，
/// 已稳定的字符保持不透明；shrink（如 flush 把尾部"吃掉"）则瞬时收敛。
///
/// 整体动画仅触发本地小区域重绘，不会拉动外层 markdown 子树。
class _AnimatedStreamingTail extends StatefulWidget {
  const _AnimatedStreamingTail({
    super.key,
    required this.text,
    required this.style,
    this.trailing,
  });

  final String text;
  final TextStyle style;
  final Widget? trailing;

  @override
  State<_AnimatedStreamingTail> createState() => _AnimatedStreamingTailState();
}

class _AnimatedStreamingTailState extends State<_AnimatedStreamingTail>
    with SingleTickerProviderStateMixin {
  static const Duration _kFadeDuration = Duration(milliseconds: 220);

  late final AnimationController _controller;
  int _frozenLength = 0;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: _kFadeDuration,
      value: 1.0,
    );
    _frozenLength = widget.text.length;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(_AnimatedStreamingTail oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.text == oldWidget.text) {
      return;
    }
    if (widget.text.length > oldWidget.text.length &&
        widget.text.startsWith(oldWidget.text)) {
      _frozenLength = oldWidget.text.length;
      _controller.forward(from: 0.0);
    } else {
      // 文本回退或被替换：放弃动画，直接到末态。
      _frozenLength = widget.text.length;
      _controller.value = 1.0;
    }
  }

  @override
  Widget build(BuildContext context) {
    final hasTrailing = widget.trailing != null;
    if (widget.text.isEmpty && !hasTrailing) {
      return const SizedBox.shrink();
    }
    return RepaintBoundary(
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) {
          final t = _controller.value;
          final frozenLen = _frozenLength.clamp(0, widget.text.length);
          final frozenText = widget.text.substring(0, frozenLen);
          final freshText = widget.text.substring(frozenLen);
          final baseColor = widget.style.color;
          final freshOpacity = (0.3 + 0.7 * t).clamp(0.0, 1.0);
          final freshColor = baseColor?.withValues(
            alpha: (baseColor.a) * freshOpacity,
          );

          return Text.rich(
            TextSpan(
              style: widget.style,
              children: <InlineSpan>[
                if (frozenText.isNotEmpty) TextSpan(text: frozenText),
                if (freshText.isNotEmpty)
                  TextSpan(
                    text: freshText,
                    style: widget.style.copyWith(color: freshColor),
                  ),
                if (hasTrailing)
                  WidgetSpan(
                    alignment: PlaceholderAlignment.middle,
                    child: Padding(
                      padding: const EdgeInsets.only(left: 4),
                      child: widget.trailing!,
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}
