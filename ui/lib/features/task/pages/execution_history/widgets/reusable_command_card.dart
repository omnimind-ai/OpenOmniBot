import 'package:flutter/material.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';

class ReusableCommandStepPreview {
  const ReusableCommandStepPreview({
    required this.index,
    required this.title,
    required this.tool,
    this.executor = '',
    this.kind = '',
  });

  final int index;
  final String title;
  final String tool;
  final String executor;
  final String kind;

  String get displayTool {
    final toolText = tool.trim();
    if (toolText.isNotEmpty) return toolText;
    final executorText = executor.trim();
    if (executorText.isNotEmpty) return executorText;
    return kind.trim();
  }
}

class ReusableCommandCardAction {
  const ReusableCommandCardAction({
    required this.icon,
    required this.tooltip,
    required this.onTap,
    required this.color,
    this.backgroundColor,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final Color color;
  final Color? backgroundColor;
}

class ReusableCommandCard extends StatelessWidget {
  const ReusableCommandCard({
    super.key,
    required this.title,
    required this.description,
    required this.steps,
    required this.stepCount,
    required this.parameterCount,
    required this.sourceRunCount,
    required this.isRunning,
    required this.onRun,
    this.isBusy = false,
    this.actions = const <ReusableCommandCardAction>[],
  });

  final String title;
  final String description;
  final List<ReusableCommandStepPreview> steps;
  final int stepCount;
  final int parameterCount;
  final int sourceRunCount;
  final bool isRunning;
  final VoidCallback? onRun;
  final bool isBusy;
  final List<ReusableCommandCardAction> actions;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final displayTitle = title.trim().isEmpty
        ? _text(context, '复用指令', 'Reusable command')
        : title.trim();
    final summaryText = description.trim();
    final previewText = _buildStepPreview(context, steps);
    return Material(
      color: Colors.transparent,
      child: Container(
        width: double.infinity,
        decoration: BoxDecoration(
          color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: context.isDarkTheme
              ? const []
              : [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.04),
                    blurRadius: 8,
                    offset: const Offset(0, 2),
                  ),
                ],
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 13, 14, 13),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _RunActionButton(isRunning: isRunning, onTap: onRun),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          displayTitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            color: palette.textPrimary,
                            height: 1.35,
                            letterSpacing: 0,
                          ),
                        ),
                        if (summaryText.isNotEmpty) ...[
                          const SizedBox(height: 3),
                          Text(
                            summaryText,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 13,
                              color: palette.textSecondary,
                              height: 1.35,
                              letterSpacing: 0,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (isBusy || actions.isNotEmpty) ...[
                    const SizedBox(width: 10),
                    if (isBusy)
                      const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    else
                      for (final action in actions)
                        Padding(
                          padding: const EdgeInsets.only(left: 4),
                          child: _IconActionButton(action: action),
                        ),
                  ],
                ],
              ),
              if (previewText.isNotEmpty) ...[
                const SizedBox(height: 9),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      Icons.subject_rounded,
                      size: 14,
                      color: palette.textTertiary,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        previewText,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textTertiary,
                          height: 1.35,
                          letterSpacing: 0,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
              const SizedBox(height: 8),
              Wrap(
                spacing: 7,
                runSpacing: 7,
                children: [
                  _MetricPill(
                    label: _text(context, '步骤', 'Steps'),
                    value: stepCount.toString(),
                  ),
                  _MetricPill(
                    label: _text(context, '参数', 'Params'),
                    value: parameterCount.toString(),
                  ),
                  if (sourceRunCount > 0)
                    _MetricPill(
                      label: 'RunLogs',
                      value: sourceRunCount.toString(),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RunActionButton extends StatelessWidget {
  const _RunActionButton({required this.isRunning, required this.onTap});

  final bool isRunning;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final label = isRunning
        ? _text(context, '执行中', 'Running')
        : _text(context, '执行', 'Run');
    return Align(
      alignment: Alignment.centerLeft,
      child: FilledButton.icon(
        onPressed: isRunning ? null : onTap,
        icon: isRunning
            ? const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              )
            : const Icon(Icons.play_arrow_rounded, size: 17),
        label: Text(label),
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 32),
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          textStyle: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            letterSpacing: 0,
          ),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
      ),
    );
  }
}

class _MetricPill extends StatelessWidget {
  const _MetricPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.accentPrimary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(99),
      ),
      child: Text(
        '$label $value',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: palette.textSecondary,
          letterSpacing: 0,
        ),
      ),
    );
  }
}

class _IconActionButton extends StatelessWidget {
  const _IconActionButton({required this.action});

  final ReusableCommandCardAction action;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: action.tooltip,
      child: GestureDetector(
        onTap: action.onTap,
        child: Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: action.backgroundColor ?? Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          alignment: Alignment.center,
          child: Icon(action.icon, size: 20, color: action.color),
        ),
      ),
    );
  }
}

String _buildStepPreview(
  BuildContext context,
  List<ReusableCommandStepPreview> steps,
) {
  final parts = <String>[];
  for (final step in steps.take(3)) {
    final title = step.title.trim();
    final tool = step.displayTool.trim();
    final text = title.isNotEmpty
        ? title
        : _localizedToolAction(context, tool).trim();
    if (text.isEmpty) continue;
    parts.add('${step.index + 1}. $text');
  }
  if (parts.isEmpty) return '';
  final preview = parts.join('  ');
  return steps.length > 3 ? '$preview ...' : preview;
}

String _localizedToolAction(BuildContext context, String tool) {
  switch (tool.trim().toLowerCase()) {
    case 'open_app':
      return _text(context, '打开应用', 'Open app');
    case 'click':
      return _text(context, '点击', 'Tap');
    case 'long_press':
      return _text(context, '长按', 'Long press');
    case 'input_text':
    case 'type':
      return _text(context, '输入文本', 'Enter text');
    case 'swipe':
    case 'scroll':
      return _text(context, '滑动', 'Swipe');
    case 'press_back':
    case 'back':
      return _text(context, '返回', 'Go back');
    case 'press_home':
    case 'home':
      return _text(context, '回到桌面', 'Go home');
    case 'wait':
      return _text(context, '等待', 'Wait');
  }
  return tool.trim();
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}
