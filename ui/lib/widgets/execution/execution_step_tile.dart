import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/execution/execution_models.dart';

/// 单个执行步骤的展示组件
class ExecutionStepTile extends StatelessWidget {
  final ExecutionStep step;
  final bool expanded;
  final VoidCallback? onTap;
  final VoidCallback? onCopyJson;

  const ExecutionStepTile({
    super.key,
    required this.step,
    this.expanded = false,
    this.onTap,
    this.onCopyJson,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;

    // 获取本地化的 route/reuse label
    String? localizedCompileLabel;
    if (step.compileKind == CompileKind.hit) {
      localizedCompileLabel = step.compileFunctionId != null
          ? l10n.executionCompileHitWithFunction(step.compileFunctionId!)
          : l10n.executionCompileHit;
    } else if (step.compileKind == CompileKind.miss) {
      localizedCompileLabel = l10n.executionVlmExecution;
    }
    // 如果没有路由类型，但有旧数据标签，使用它
    final displayCompileLabel = localizedCompileLabel ?? step.routeLabel;
    final isCompileHit = step.compileKind == CompileKind.hit;

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 标题行
              Row(
                children: [
                  // 步骤序号
                  Container(
                    width: 24,
                    height: 24,
                    decoration: BoxDecoration(
                      color: step.success == false
                          ? const Color(0xFFFDECEC)
                          : palette.surfaceSecondary,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      '${step.index + 1}',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: step.success == false
                            ? const Color(0xFFB42318)
                            : palette.textSecondary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  // 动作名称
                  Expanded(
                    child: Text(
                      _getLocalizedDisplayName(context, step.actionType),
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                  // Route/reuse 标签
                  if (displayCompileLabel != null) ...[
                    _buildPill(
                      context,
                      displayCompileLabel,
                      backgroundColor: isCompileHit
                          ? const Color(0xFFE8F7EE)
                          : palette.surfaceSecondary,
                      textColor: isCompileHit
                          ? const Color(0xFF117A37)
                          : palette.textSecondary,
                    ),
                    const SizedBox(width: 8),
                  ],
                  if (step.tokenUsage?.compactText.isNotEmpty == true) ...[
                    _buildPill(
                      context,
                      step.tokenUsage!.compactText,
                      backgroundColor: palette.surfaceSecondary,
                      textColor: palette.textSecondary,
                    ),
                    const SizedBox(width: 8),
                  ],
                  // 展开/收起图标
                  if (onTap != null)
                    Icon(
                      expanded ? Icons.expand_less : Icons.expand_more,
                      color: palette.textTertiary,
                      size: 20,
                    ),
                ],
              ),

              // 目标描述
              if (step.targetDescription != null &&
                  step.targetDescription!.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  step.targetDescription!,
                  style: TextStyle(
                    fontSize: 13,
                    color: palette.textSecondary,
                    height: 1.4,
                  ),
                  maxLines: expanded ? null : 2,
                  overflow: expanded ? null : TextOverflow.ellipsis,
                ),
              ],

              // 展开的详细信息
              if (expanded) ...[
                const SizedBox(height: 10),
                _buildExpandedContent(context),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildExpandedContent(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    final params = step.params;

    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 参数列表
          if (params.isNotEmpty) ...[
            ...params.entries.map(
              (e) => Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${e.key}: ',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: palette.textSecondary,
                      ),
                    ),
                    Expanded(
                      child: Text(
                        '${e.value}',
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textPrimary,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 8),
          ],

          // 时间和结果
          Row(
            children: [
              if (step.durationMs != null) ...[
                Icon(
                  Icons.timer_outlined,
                  size: 14,
                  color: palette.textTertiary,
                ),
                const SizedBox(width: 4),
                Text(
                  '${(step.durationMs! / 1000).toStringAsFixed(2)}s',
                  style: TextStyle(fontSize: 11, color: palette.textTertiary),
                ),
                const SizedBox(width: 12),
              ],
              if (step.success != null) ...[
                Icon(
                  step.success! ? Icons.check_circle : Icons.error,
                  size: 14,
                  color: step.success!
                      ? const Color(0xFF117A37)
                      : const Color(0xFFB42318),
                ),
                const SizedBox(width: 4),
                Text(
                  step.success! ? l10n.executionSuccess : l10n.executionFailed,
                  style: TextStyle(
                    fontSize: 11,
                    color: step.success!
                        ? const Color(0xFF117A37)
                        : const Color(0xFFB42318),
                  ),
                ),
              ],
              if (step.tokenUsage?.compactText.isNotEmpty == true) ...[
                const SizedBox(width: 12),
                Icon(
                  Icons.data_usage_rounded,
                  size: 14,
                  color: palette.textTertiary,
                ),
                const SizedBox(width: 4),
                Flexible(
                  child: Text(
                    step.tokenUsage!.compactText,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11, color: palette.textTertiary),
                  ),
                ),
              ],
              const Spacer(),
              if (onCopyJson != null)
                TextButton(
                  onPressed: onCopyJson,
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: Text(
                    'Copy JSON',
                    style: const TextStyle(fontSize: 11),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  /// 获取本地化的动作显示名称
  String _getLocalizedDisplayName(BuildContext context, String actionType) {
    final l10n = context.l10n;
    switch (actionType.trim().toLowerCase()) {
      case 'open_app':
        return l10n.executionActionOpenApp;
      case 'click':
        return l10n.executionActionClick;
      case 'click_node':
        return l10n.executionActionClickNode;
      case 'long_press':
        return l10n.executionActionLongPress;
      case 'input_text':
        return l10n.executionActionInputText;
      case 'swipe':
        return l10n.executionActionSwipe;
      case 'scroll':
        return l10n.executionActionScroll;
      case 'press_key':
        return l10n.executionActionPressKey;
      case 'finished':
        return l10n.executionActionFinished;
      case 'call_function':
        return l10n.executionActionCallFunction;
      default:
        return actionType.trim().isEmpty
            ? l10n.executionActionDefault
            : actionType.trim();
    }
  }

  Widget _buildPill(
    BuildContext context,
    String text, {
    Color? backgroundColor,
    Color? textColor,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: backgroundColor ?? palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: textColor ?? palette.textSecondary,
        ),
      ),
    );
  }
}
