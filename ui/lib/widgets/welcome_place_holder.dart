import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class WelcomePlaceHolder extends StatefulWidget {
  final ValueChanged<String>? onSuggestionTap;
  final List<String>? suggestions; // if null, use defaults
  final bool showSuggestions; // control when to animate insert
  final FocusNode focusNode; // 接收外部传入的 FocusNode

  const WelcomePlaceHolder({
    super.key,
    this.onSuggestionTap,
    this.suggestions,
    this.showSuggestions = false,
    required this.focusNode, // 必填参数
  });

  @override
  State<WelcomePlaceHolder> createState() => _WelcomePlaceHolderState();
}

class _WelcomePlaceHolderState extends State<WelcomePlaceHolder> {
  final GlobalKey<AnimatedListState> _suggestionListKey = GlobalKey<AnimatedListState>();
  final ScrollController _scrollController = ScrollController(); // 添加滚动控制器
  final List<String> _rendered = [];
  bool _hasInserted = false;

  static const _defaultSuggestions = [
        '📷 Take a photo with camera',
        '📅 Create a meeting reminder for tomorrow morning',
        '🛫 Search for flights from Beijing to Shanghai',
      ];

  static const _defaultSuggestionsZh = [
        '📷 打开相机并拍一张照片',
        '📅 创建明天上午的会议提醒',
        '🛫 查询北京飞上海的机票',
      ];

  List<String> get _allSuggestions => widget.suggestions ?? (LegacyTextLocalizer.isEnglish ? _defaultSuggestions : _defaultSuggestionsZh);

  @override
  void initState() {
    super.initState();
    widget.focusNode.addListener(_onFocusChange); // 使用外部传入的 FocusNode
    if (widget.showSuggestions) {
      Future.delayed(const Duration(milliseconds: 150), _insertSuggestionsAnimated);
    }
  }

  @override
  void dispose() {
    _scrollController.dispose(); // 释放滚动控制器
    widget.focusNode.removeListener(_onFocusChange); // 移除监听器
    super.dispose();
  }

  void _onFocusChange() {
    if (widget.focusNode.hasFocus) {
      Future.delayed(const Duration(milliseconds: 300), () {
        _scrollToBottom();
      });
    }
  }

  void _scrollToBottom() {
    Future.delayed(const Duration(milliseconds: 100), () {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  void didUpdateWidget(covariant WelcomePlaceHolder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.showSuggestions && !_hasInserted) {
      Future.delayed(const Duration(milliseconds: 50), _insertSuggestionsAnimated);
    }
  }


  void _insertSuggestionsAnimated() async {
    if (_hasInserted) return;
    for (int i = 0; i < _allSuggestions.length; i++) {
      _rendered.insert(i, _allSuggestions[i]);
      _suggestionListKey.currentState?.insertItem(i, duration: const Duration(milliseconds: 300));
      await Future.delayed(const Duration(milliseconds: 360));
    }
    setState(() {
      _hasInserted = true; // 确保状态更新后换一换按钮显示
    });
  }

  @override
  Widget build(BuildContext context) {
    const darkGrey = Color(0xFF666666);
    return SingleChildScrollView(
      controller: _scrollController, // 绑定滚动控制器
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(height: 100),
          Container(
            width: 160,
            height: 140,
            child: Image.asset(
              'assets/images/welcome.png',
              fit: BoxFit.contain,
              errorBuilder: (context, error, stackTrace) => Container(
                width: 160,
                height: 140,
                decoration: BoxDecoration(
                  color: Colors.pink[50],
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
            ),
          ),
          SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24.0),
            child: Text(
              LegacyTextLocalizer.localize('🎉Hi，我是小万，我会做很多事，让我展示给你下！'),
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 16,
                color: darkGrey,
              ),
            ),
          ),
          const SizedBox(height: 16),
          AnimatedList(
            key: _suggestionListKey,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            padding: const EdgeInsets.symmetric(horizontal: 24),
            initialItemCount: _rendered.length,
            itemBuilder: (context, index, animation) {
              final text = _rendered[index];
              return SizeTransition(
                sizeFactor: CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
                child: FadeTransition(
                  opacity: animation,
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 8.0),
                    child: _SuggestionChip(
                      text: text,
                      onTap: () => widget.onSuggestionTap?.call(text),
                    ),
                  ),
                ),
              );
            },
          ),
          if (_hasInserted) // 换一换按钮
            Align(
              alignment: Alignment.centerLeft,
              child: Padding(
                padding: const EdgeInsets.only(top: 8.0,left: 24.0),
                child: GestureDetector(
                  onTap: () {
                    // not implemented yet
                  },
                  child: Row(
                    children: [
                      Icon(
                        Icons.refresh,
                        size: 16,
                        color: darkGrey,
                      ),
                      SizedBox(width: 8),
                      Text(
                        LegacyTextLocalizer.localize("换一换"),
                        style: const TextStyle(
                          color: darkGrey,
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          SizedBox(height: 180),
        ],
      ),
    );
  }
}

class _SuggestionChip extends StatelessWidget {
  final String text;
  final VoidCallback? onTap;
  const _SuggestionChip({required this.text, this.onTap});

  @override
  Widget build(BuildContext context) {
    const darkGrey = Color(0xFF666666);
    final theme = Theme.of(context);
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14.0, vertical: 10.0),
        decoration: BoxDecoration(
          color: theme.colorScheme.surface,
          borderRadius: BorderRadius.circular(8.0),
          border: Border.all(color: Colors.grey.shade300, width: 1),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Flexible(
              child: Text(
                text,
                style: TextStyle(
                  color: darkGrey,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            Icon(
              Icons.call_made,
              size: 16,
              color: darkGrey,
            ),
          ],
        ),
      ),
    );
  }
}