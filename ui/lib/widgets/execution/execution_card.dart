import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/execution/execution_models.dart';
import 'package:ui/widgets/execution/execution_stats_panel.dart';

/// 执行卡片组件
/// 用于在列表中显示 Function 或 RunLog 的概要信息
class ExecutionCard extends StatelessWidget {
  final ExecutionDetail detail;
  final bool highlighted;
  final VoidCallback? onTap;
  final VoidCallback? onExecute;
  final VoidCallback? onViewDetail;
  final VoidCallback? onDelete;
  final Widget? trailing;

  const ExecutionCard({
    super.key,
    required this.detail,
    this.highlighted = false,
    this.onTap,
    this.onExecute,
    this.onViewDetail,
    this.onDelete,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Container(
      decoration: BoxDecoration(
        color: highlighted ? const Color(0xFFFFFBF0) : palette.surfacePrimary,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: highlighted ? const Color(0xFFF59E0B) : Colors.transparent,
          width: highlighted ? 1.5 : 0,
        ),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor,
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 标题行
              Row(
                children: [
                  Expanded(
                    child: Text(
                      detail.id,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                  if (trailing != null) trailing!,
                ],
              ),

              // 描述
              if (detail.goal != null && detail.goal!.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  detail.goal!,
                  style: TextStyle(
                    fontSize: 13,
                    color: palette.textSecondary,
                    height: 1.4,
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],

              // 标签行
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  // 类型标签
                  _buildPill(
                    context,
                    detail.type == ExecutionDetailType.function
                        ? 'Function'
                        : 'Run Log',
                    backgroundColor: detail.type == ExecutionDetailType.function
                        ? const Color(0xFFE8F0FF)
                        : const Color(0xFFF0E8FF),
                    textColor: detail.type == ExecutionDetailType.function
                        ? const Color(0xFF1E40AF)
                        : const Color(0xFF6B21A8),
                  ),
                  // 步骤数
                  _buildPill(context, '${detail.stepCount} steps'),
                  if (detail.statusText.isNotEmpty)
                    _buildPill(
                      context,
                      detail.statusText,
                      backgroundColor: _statusBackgroundColor(detail),
                      textColor: _statusTextColor(detail),
                    ),
                  // 应用名称
                  if (detail.appName != null && detail.appName!.isNotEmpty)
                    _buildPill(context, detail.appName!),
                  if (detail.type == ExecutionDetailType.runLog &&
                      detail.taskType != null &&
                      detail.taskType!.isNotEmpty)
                    _buildPill(context, detail.taskType!),
                  if (detail.type == ExecutionDetailType.runLog &&
                      detail.replayable)
                    _buildPill(
                      context,
                      '可回放',
                      backgroundColor: const Color(0xFFE8F7EE),
                      textColor: const Color(0xFF117A37),
                    ),
                  // 高亮标签
                  if (highlighted) _buildPill(context, '新导入'),
                ],
              ),

              // 统计信息（仅 Function）
              if (detail.type == ExecutionDetailType.function &&
                  detail.stats != null &&
                  detail.stats!.callCount > 0) ...[
                const SizedBox(height: 12),
                ExecutionStatsPanel(stats: detail.stats!, compact: true),
              ],

              // 时间信息（仅 RunLog）
              if (detail.type == ExecutionDetailType.runLog &&
                  (detail.durationMs != null || detail.startedAt != null)) ...[
                const SizedBox(height: 10),
                Row(
                  children: [
                    if (detail.durationMs != null) ...[
                      Icon(
                        Icons.timer_outlined,
                        size: 14,
                        color: palette.textTertiary,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        detail.durationText,
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textTertiary,
                        ),
                      ),
                      const SizedBox(width: 12),
                    ],
                    if (detail.startedAt != null) ...[
                      Icon(
                        Icons.schedule,
                        size: 14,
                        color: palette.textTertiary,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        _formatTime(detail.startedAt!),
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textTertiary,
                        ),
                      ),
                    ],
                  ],
                ),
              ],

              // 操作按钮
              if (onExecute != null ||
                  onViewDetail != null ||
                  onDelete != null) ...[
                const SizedBox(height: 14),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    if (onViewDetail != null)
                      OutlinedButton.icon(
                        onPressed: onViewDetail,
                        icon: const Icon(Icons.visibility_outlined, size: 16),
                        label: const Text('查看'),
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 8,
                          ),
                          minimumSize: Size.zero,
                        ),
                      ),
                    if (onViewDetail != null && onDelete != null)
                      const SizedBox(width: 8),
                    if (onDelete != null)
                      OutlinedButton.icon(
                        onPressed: onDelete,
                        icon: const Icon(Icons.delete_outline, size: 16),
                        label: const Text('删除'),
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 8,
                          ),
                          minimumSize: Size.zero,
                          foregroundColor: const Color(0xFFB42318),
                        ),
                      ),
                    if (onDelete != null && onExecute != null)
                      const SizedBox(width: 8),
                    if (onExecute != null)
                      FilledButton.icon(
                        onPressed: onExecute,
                        icon: const Icon(Icons.play_arrow, size: 16),
                        label: const Text('执行'),
                        style: FilledButton.styleFrom(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 8,
                          ),
                          minimumSize: Size.zero,
                        ),
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPill(
    BuildContext context,
    String text, {
    Color? backgroundColor,
    Color? textColor,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: backgroundColor ?? palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: textColor ?? palette.textSecondary,
        ),
      ),
    );
  }

  String _formatTime(String isoTime) {
    try {
      final dt = DateTime.parse(isoTime);
      final now = DateTime.now();
      final diff = now.difference(dt);
      if (diff.inMinutes < 1) return '刚刚';
      if (diff.inHours < 1) return '${diff.inMinutes}分钟前';
      if (diff.inDays < 1) return '${diff.inHours}小时前';
      return '${dt.month}/${dt.day} ${dt.hour}:${dt.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return isoTime;
    }
  }

  Color _statusBackgroundColor(ExecutionDetail detail) {
    if (detail.statusIsPositive) return const Color(0xFFE8F7EE);
    if (detail.statusIsWarning) return const Color(0xFFFFF7E6);
    return const Color(0xFFFDECEC);
  }

  Color _statusTextColor(ExecutionDetail detail) {
    if (detail.statusIsPositive) return const Color(0xFF117A37);
    if (detail.statusIsWarning) return const Color(0xFF9A5B00);
    return const Color(0xFFB42318);
  }
}
