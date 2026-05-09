import 'dart:async';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import './bot_status.dart';

/// 阶段提示卡片组件
/// 用于展示多阶段调用时的提示文案（如"正在识别你的需求类型…"、"规划任务中…"）
class StageHintCard extends StatefulWidget {
  /// 提示文案
  final String hint;

  /// 阶段开始时间（用于计算耗时）
  final DateTime? startTime;

  const StageHintCard({
    super.key,
    required this.hint,
    this.startTime,
  });

  @override
  State<StageHintCard> createState() => _StageHintCardState();
}

class _StageHintCardState extends State<StageHintCard> {
  Timer? _timer;
  int _elapsedSeconds = 0;

  @override
  void initState() {
    super.initState();
    if (widget.startTime != null) {
      _startTimer();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (widget.startTime != null) {
        setState(() {
          _elapsedSeconds = DateTime.now().difference(widget.startTime!).inSeconds;
        });
      }
    });
  }

  String _formatTime(int seconds) {
    if (seconds < 60) {
      return LegacyTextLocalizer.localize('$seconds 秒');
    } else {
      final minutes = seconds ~/ 60;
      final remainingSeconds = seconds % 60;
      return LegacyTextLocalizer.localize('$minutes 分 $remainingSeconds 秒');
    }
  }

  @override
  Widget build(BuildContext context) {
    return BotStatus(
      status: BotStatusType.hint,
      hintText: LegacyTextLocalizer.localize(widget.hint),
      costTime: _elapsedSeconds > 0 ? _formatTime(_elapsedSeconds) : null,
    );
  }
}
