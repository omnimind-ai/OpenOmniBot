import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/theme_context.dart';

class BotStatus extends StatelessWidget {
  final BotStatusType status;
  final String? hintText;
  final String? costTime;

  const BotStatus({
    super.key,
    required this.status,
    this.hintText,
    this.costTime,
  });

  @override
  Widget build(BuildContext context) {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    if (status == BotStatusType.completed && isEnglish) {
      final completedText = (costTime != null && costTime!.trim().isNotEmpty)
          ? 'Thought for ${costTime!.trim()}'
          : 'Thought complete';
      return _buildStatusRow(
        context,
        icon: Icons.check_circle,
        text: completedText,
      );
    }

    switch (status) {
      case BotStatusType.thinking:
        return _buildStatusRow(
          context,
          svgPath: 'assets/chatbot/thinking_icon.svg',
          text: Localizations.localeOf(context).languageCode == 'en'
              ? 'Thinking...'
              : '正在思考...',
          timeDesc: Localizations.localeOf(context).languageCode == 'en'
              ? 'Time taken'
              : '已用时',
          costTime: costTime,
        );
      case BotStatusType.completed:
        return _buildStatusRow(
          context,
          icon: Icons.check_circle,
          text: Localizations.localeOf(context).languageCode == 'en'
              ? 'Thinking complete'
              : '已完成思考',
          timeDesc: Localizations.localeOf(context).languageCode == 'en'
              ? 'Total time'
              : '总用时',
          costTime: costTime,
        );
      case BotStatusType.hint:
        return _buildStatusRow(
          context,
          svgPath: 'assets/chatbot/thinking_icon.svg',
          text:
              hintText ??
              (Localizations.localeOf(context).languageCode == 'en'
                  ? 'Hint'
                  : '提示'),
        );
    }
  }

  Widget _buildStatusRow(
    BuildContext context, {
    IconData? icon,
    String? svgPath,
    required String text,
    String? timeDesc,
    String? costTime,
  }) {
    final palette = context.omniPalette;
    final lightGrey = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF999999);

    Widget iconWidget;
    if (svgPath != null) {
      iconWidget = SvgPicture.asset(
        svgPath,
        width: 12,
        height: 12,
        colorFilter: context.isDarkTheme
            ? ColorFilter.mode(lightGrey, BlendMode.srcIn)
            : null,
      );
    } else if (icon != null) {
      iconWidget = Icon(icon, size: 16, color: lightGrey);
    } else {
      iconWidget = const SizedBox.shrink();
    }

    return Container(
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(8.0)),
      child: Row(
        children: [
          iconWidget,
          const SizedBox(width: 8.0),
          Text(
            text,
            style: TextStyle(
              color: lightGrey,
              fontSize: 12,
              fontFamily: 'PingFang SC',
              fontWeight: FontWeight.w400,
              height: 1.50,
              letterSpacing: 0.33,
            ),
          ),
          const SizedBox(width: 4.0),
          Text(
            timeDesc != null ? '$timeDesc ${costTime ?? ''}' : '',
            style: TextStyle(
              color: lightGrey,
              fontSize: 12,
              fontFamily: 'PingFang SC',
              fontWeight: FontWeight.w400,
              height: 1.50,
              letterSpacing: 0.33,
            ),
          ),
        ],
      ),
    );
  }
}

enum BotStatusType { thinking, completed, hint }
