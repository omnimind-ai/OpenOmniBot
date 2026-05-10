import 'dart:async';
import 'package:flutter/material.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';

class TypewriterText extends StatefulWidget {
  final String text;
  final TextStyle style;
  final bool shouldAnimate;
  final VoidCallback? onCharacterTyped;
  final VoidCallback? onAnimationCompleted;

  const TypewriterText({
    super.key,
    required this.text,
    required this.style,
    required this.shouldAnimate,
    this.onCharacterTyped,
    this.onAnimationCompleted,
  });

  @override
  State<TypewriterText> createState() => _TypewriterTextState();
}

class _TypewriterTextState extends State<TypewriterText> {
  String _displayedText = "";
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    if (widget.shouldAnimate) {
      _startTyping();
    } else {
      _displayedText = widget.text;
    }
  }

  void _startTyping() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 15), (timer) {
      if (_displayedText.length < widget.text.length) {
        if (mounted) {
          setState(() {
            final nextLength = _nextSafePrefixLength(
              widget.text,
              _displayedText.length,
            );
            _displayedText = widget.text.substring(0, nextLength);
            widget.onCharacterTyped?.call();
          });
        }
      } else {
        _timer?.cancel();
        widget.onAnimationCompleted?.call();
      }
    });
  }

  @override
  void didUpdateWidget(covariant TypewriterText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text) {
      _timer?.cancel();
      if (widget.shouldAnimate) {
        setState(() {
          _displayedText = "";
        });
        _startTyping();
      } else {
        setState(() {
          _displayedText = widget.text;
        });
      }
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Use a non-breaking space to prevent widget collapse when text is empty.
    final textToDisplay = _displayedText.isEmpty ? '\u200B' : _displayedText;

    return OmnibotMarkdownBody(
      data: textToDisplay,
      baseStyle: widget.style,
      selectable: true,
    );
  }

  int _nextSafePrefixLength(String text, int currentLength) {
    var nextLength = (currentLength + 1).clamp(0, text.length);
    if (nextLength <= 0 || nextLength >= text.length) {
      return nextLength;
    }
    final previousUnit = text.codeUnitAt(nextLength - 1);
    final currentUnit = text.codeUnitAt(nextLength);
    final isPreviousHighSurrogate =
        previousUnit >= 0xD800 && previousUnit <= 0xDBFF;
    final isCurrentLowSurrogate =
        currentUnit >= 0xDC00 && currentUnit <= 0xDFFF;
    if (isPreviousHighSurrogate && isCurrentLowSurrogate) {
      nextLength += 1;
    }
    return nextLength.clamp(0, text.length);
  }
}
